package com.ashoo.correlation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ashoo.storage.entity.CorrelationResult;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.RiskScoreHistory;
import com.ashoo.storage.repository.CorrelationResultRepository;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.RiskScoreHistoryRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Turns the latest environmental snapshot into a Personal Risk Index using the
 * weights and thresholds learned by {@link CorrelationService}.
 *
 * Two entry points with deliberately different side effects: {@link #scoreAndPersist}
 * is the hourly job that writes a row and advances the EWMA/hysteresis chain, while
 * {@link #currentBreakdown} is a read-only preview for the dashboard that computes the
 * same number without polluting the history series with a row per page refresh.
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    /** Window of history used as the reference distribution for percentile normalization. */
    private static final int HISTORY_DAYS = 180;

    private final EnvironmentalSnapshotRepository snapshotRepo;
    private final CorrelationResultRepository correlationRepo;
    private final RiskScoreHistoryRepository riskHistoryRepo;
    private final SymptomLogRepository symptomRepo;
    private final PercentileNormalizer normalizer;
    private final RiskScorer riskScorer;
    private final ObjectMapper objectMapper;

    public RiskScoringService(EnvironmentalSnapshotRepository snapshotRepo,
                              CorrelationResultRepository correlationRepo,
                              RiskScoreHistoryRepository riskHistoryRepo,
                              SymptomLogRepository symptomRepo,
                              PercentileNormalizer normalizer,
                              RiskScorer riskScorer,
                              ObjectMapper objectMapper) {
        this.snapshotRepo = snapshotRepo;
        this.correlationRepo = correlationRepo;
        this.riskHistoryRepo = riskHistoryRepo;
        this.symptomRepo = symptomRepo;
        this.normalizer = normalizer;
        this.riskScorer = riskScorer;
        this.objectMapper = objectMapper;
    }

    /**
     * Scores the latest conditions and writes a row to risk_score_history.
     *
     * Called by the hourly scheduler after ingestion. Advances the EWMA and hysteresis
     * state by reading the prior row, so the persisted series is a continuous smoothed
     * signal rather than a set of independent snapshots.
     *
     * @param userId the user whose model and history to use (also the environment owner in V1)
     * @return the breakdown that was persisted, or empty if there's nothing to score yet
     */
    public Optional<RiskScoreBreakdown> scoreAndPersist(String userId) {
        return scoreAndPersist(userId, userId);
    }

    /**
     * Scores the latest conditions for a symptom/environment user pair and persists it.
     *
     * @param userId    the user whose model (weights) and score history to use
     * @param envUserId the user whose environmental snapshots to read
     * @return the persisted breakdown, or empty if no snapshot or no model exists yet
     */
    public Optional<RiskScoreBreakdown> scoreAndPersist(String userId, String envUserId) {
        Optional<RiskScoreBreakdown> breakdown = computeBreakdown(userId, envUserId);
        breakdown.ifPresent(b -> persist(userId, b));
        return breakdown;
    }

    /**
     * Computes the current breakdown without persisting it — the dashboard's live view.
     *
     * @param userId    the user whose model and history to use
     * @param envUserId the user whose environment to read
     * @return the breakdown, or empty if no snapshot or no model exists yet
     */
    public Optional<RiskScoreBreakdown> currentBreakdown(String userId, String envUserId) {
        return computeBreakdown(userId, envUserId);
    }

    /**
     * Core scoring logic shared by the persisting and preview paths.
     *
     * Returns empty (rather than a zero score) when prerequisites are missing — no
     * current snapshot, or no correlation model yet — so callers can tell "we don't
     * know" apart from "we computed a genuine low score."
     */
    private Optional<RiskScoreBreakdown> computeBreakdown(String userId, String envUserId) {
        Optional<EnvironmentalSnapshot> latestOpt = snapshotRepo.findLatest(envUserId);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }
        List<CorrelationResult> model = correlationRepo.findByUserId(userId);
        if (model.isEmpty()) {
            return Optional.empty();
        }
        EnvironmentalSnapshot latest = latestOpt.get();

        // Reference distribution for normalization: all readings in the history window.
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(HISTORY_DAYS));
        List<EnvironmentalSnapshot> history = snapshotRepo.findByDateRange(envUserId, from, to);
        Map<Factor, List<Double>> historyByFactor = collectHistory(history);

        Map<String, Double> normalizedScores = new HashMap<>();
        Map<String, Double> weights = new HashMap<>();
        List<FactorContribution> contributions = new ArrayList<>();

        for (CorrelationResult row : model) {
            Factor factor = Factor.fromKey(row.getFactorName());
            if (factor == null) continue;
            Double value = factor.extract(latest);
            if (value == null) continue;

            double weight = row.getWeight() != null ? row.getWeight() : 0.0;
            double percentile = normalizer.normalize(value,
                    historyByFactor.getOrDefault(factor, List.of()));
            boolean aboveThreshold = row.getPersonalThreshold() != null
                    && value >= row.getPersonalThreshold();

            normalizedScores.put(factor.getKey(), percentile);
            weights.put(factor.getKey(), weight);
            contributions.add(new FactorContribution(
                    factor.getKey(), factor.getDisplayName(), percentile, aboveThreshold, weight));
        }

        if (normalizedScores.isEmpty()) {
            return Optional.empty();
        }

        Optional<RiskScoreHistory> prior = riskHistoryRepo.findLatest(userId);
        Double prevSmoothed = prior.map(RiskScoreHistory::getRiskScoreSmoothed).orElse(null);
        boolean prevAlert = prior.map(p -> Boolean.TRUE.equals(p.getAlertTriggered())).orElse(false);

        RiskScoreResult result = riskScorer.computeScore(
                normalizedScores, weights, prevSmoothed, prevAlert);

        int symptomDays = symptomRepo.countSymptomDays(userId);
        ConfidenceLevel confidence = ConfidenceLevel.fromSymptomDays(symptomDays);

        // Strongest contributors first for the dashboard breakdown.
        contributions.sort(Comparator.comparingDouble(FactorContribution::weight).reversed());

        return Optional.of(new RiskScoreBreakdown(
                Instant.now(), result, confidence, symptomDays, contributions, normalizedScores));
    }

    private void persist(String userId, RiskScoreBreakdown b) {
        RiskScoreHistory row = new RiskScoreHistory();
        row.setScoredAt(b.scoredAt());
        row.setUserId(userId);
        row.setRiskScore(b.score().rawScore());
        row.setRiskScoreSmoothed(b.score().smoothedScore());
        row.setRiskLabel(b.score().level().getLabel());
        row.setAlertTriggered(b.score().alertActive());
        row.setFactorScores(toJson(b.normalizedScores()));
        row.setConfidenceLevel(b.confidence().name());
        row.setSymptomDaysAvailable(b.symptomDaysAvailable());
        riskHistoryRepo.save(row);
    }

    /**
     * Collects every non-null reading per factor across the window — the reference
     * distribution each current value is ranked against.
     */
    private Map<Factor, List<Double>> collectHistory(List<EnvironmentalSnapshot> history) {
        Map<Factor, List<Double>> byFactor = new EnumMap<>(Factor.class);
        for (Factor f : Factor.values()) byFactor.put(f, new ArrayList<>());
        for (EnvironmentalSnapshot s : history) {
            for (Factor f : Factor.values()) {
                Double v = f.extract(s);
                if (v != null) byFactor.get(f).add(v);
            }
        }
        return byFactor;
    }

    private String toJson(Map<String, Double> scores) {
        try {
            return objectMapper.writeValueAsString(scores);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize factor scores to JSON, storing empty object", e);
            return "{}";
        }
    }

    // ── Value types returned to the API ──────────────────────────────────────

    /**
     * One factor's contribution to the current score.
     *
     * @param key            stable factor key (e.g. "pollen_grass")
     * @param displayName    human-readable label
     * @param percentile     today's value as a 0–100 percentile of the user's history
     * @param aboveThreshold whether today's value crossed the learned personal threshold
     * @param weight         this factor's importance weight in the model
     */
    public record FactorContribution(String key, String displayName, double percentile,
                                     boolean aboveThreshold, double weight) {}

    /**
     * The full result of a scoring run.
     *
     * @param scoredAt          when this was computed
     * @param score             raw/smoothed scores, alert state, and RiskLevel
     * @param confidence        confidence from the user's symptom-day count
     * @param symptomDaysAvailable symptom days behind this score
     * @param factors           per-factor breakdown, strongest weight first
     * @param normalizedScores  raw key→percentile map (persisted as JSONB)
     */
    public record RiskScoreBreakdown(Instant scoredAt, RiskScoreResult score,
                                     ConfidenceLevel confidence, int symptomDaysAvailable,
                                     List<FactorContribution> factors,
                                     Map<String, Double> normalizedScores) {}
}
