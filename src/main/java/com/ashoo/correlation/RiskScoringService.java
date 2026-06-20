package com.ashoo.correlation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ashoo.storage.entity.CorrelationResult;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.RiskScoreHistory;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.CorrelationResultRepository;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.RiskScoreHistoryRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    /**
     * Personalizes a factor's score input by its relationship to the person's threshold.
     *
     * Crossing THIS person's learned threshold means the reading resembles conditions that
     * have preceded their symptoms, a strong personal-risk signal in its own right, so an
     * above-threshold factor contributes high (60-100) regardless of its raw percentile.
     * A below-threshold factor contributes low (0-35), scaled by how elevated it is. The
     * net effect: a sensitive person (low thresholds, so many factors are above) scores
     * notably higher than a tolerant one in the very same air, which is the whole point of
     * a PERSONAL risk index.
     *
     * @param percentile     how high today's reading is vs the person's history (0-100)
     * @param aboveThreshold whether it crossed the level that has preceded their symptoms
     * @return the value fed into the weighted score
     */
    private static double personalScoreInput(double percentile, boolean aboveThreshold) {
        return aboveThreshold ? 60.0 + 0.40 * percentile : 0.35 * percentile;
    }

    /** How the final score splits between trigger INTENSITY and trigger BREADTH. */
    private static final double INTENSITY_WEIGHT = 0.5;
    private static final double BREADTH_WEIGHT = 0.5;

    /**
     * Blends two views of personal risk into the raw 0-100 score.
     *
     * INTENSITY is the weighted average of how elevated the person's factors are
     * (a single sharp trigger can drive this high). BREADTH is the ABSOLUTE number of
     * the person's triggers that are above threshold at once. We use the count, not a
     * fraction, on purpose: a narrowly sensitive person with one strong trigger has a
     * tiny model where that trigger is most of it, so a fraction would read high and
     * defeat the point. Counting active triggers makes a broadly sensitive profile
     * (many triggers firing together) score above a single-trigger one, which is what
     * "high sensitivity" should mean.
     *
     * @param scoreInputs          per-factor personalized inputs
     * @param weights              per-factor weights
     * @param meaningfulActiveCount active triggers that ALSO carry real weight (see below)
     * @return the blended raw score (0-100)
     */
    private static double blendedRaw(Map<String, Double> scoreInputs, Map<String, Double> weights,
                                     long meaningfulActiveCount) {
        double intensity = RiskScorer.weightedMean(scoreInputs, weights);
        // ~5-6 genuine triggers firing at once saturates the breadth signal at 100.
        double breadth = Math.min(100.0, meaningfulActiveCount * 18.0);
        return INTENSITY_WEIGHT * intensity + BREADTH_WEIGHT * breadth;
    }

    /**
     * Counts active triggers that actually matter, that is, factors both above the
     * person's threshold AND carrying at least an average share of the model's weight.
     *
     * This filters out the spurious "active" factors a sparse, focused model can throw
     * off (low, near-meaningless thresholds on uncorrelated factors), so breadth
     * reflects genuine multi-trigger sensitivity rather than statistical noise.
     */
    private static long meaningfulActive(List<FactorContribution> contributions) {
        int n = contributions.size();
        if (n == 0) return 0;
        double avgWeight = 1.0 / n; // weights are normalized to sum to ~1
        return contributions.stream()
                .filter(c -> c.aboveThreshold() && c.weight() >= avgWeight)
                .count();
    }

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
     * Scores the latest conditions with the EWMA chain reset (smoothed = raw) and
     * persists the result. Used after an explicit recompute so the new model is
     * immediately visible on the dashboard without dampening from old history.
     */
    public Optional<RiskScoreBreakdown> scoreAndPersistFresh(String userId, String envUserId) {
        Optional<RiskScoreBreakdown> breakdown = doComputeBreakdown(userId, envUserId, true);
        breakdown.ifPresent(b -> persist(userId, b));
        return breakdown;
    }

    private Optional<RiskScoreBreakdown> computeBreakdown(String userId, String envUserId) {
        return doComputeBreakdown(userId, envUserId, false);
    }

    /**
     * Core scoring logic. When {@code fresh} is true the EWMA chain is reset so the
     * score immediately reflects a just-recomputed model without dampening.
     * Also blends in recent self-reported symptom severity so the PRI visibly
     * responds when the user logs or edits symptom entries.
     */
    private Optional<RiskScoreBreakdown> doComputeBreakdown(String userId, String envUserId,
                                                             boolean fresh) {
        Optional<EnvironmentalSnapshot> latestOpt = snapshotRepo.findLatest(envUserId);
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }
        List<CorrelationResult> model = correlationRepo.findByUserId(userId);
        if (model.isEmpty()) {
            return Optional.empty();
        }
        EnvironmentalSnapshot latest = latestOpt.get();

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(HISTORY_DAYS));
        List<EnvironmentalSnapshot> history = snapshotRepo.findByDateRange(envUserId, from, to);
        Map<Factor, List<Double>> historyByFactor = collectHistory(history);

        Map<String, Double> normalizedScores = new HashMap<>();
        Map<String, Double> scoreInputs = new HashMap<>();
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
            scoreInputs.put(factor.getKey(), personalScoreInput(percentile, aboveThreshold));
            weights.put(factor.getKey(), weight);
            contributions.add(new FactorContribution(
                    factor.getKey(), factor.getDisplayName(), percentile, aboveThreshold, weight,
                    value, factor.getUnit()));
        }

        if (normalizedScores.isEmpty()) {
            return Optional.empty();
        }

        double raw = blendedRaw(scoreInputs, weights, meaningfulActive(contributions));

        // Blend recent symptom severity so the score responds to logged entries.
        // Only raises the score: if the user reports worse symptoms than conditions
        // predict, the PRI reflects that; mild symptoms don't lower the env signal.
        double symptomSignal = recentSymptomSignal(userId);
        if (symptomSignal > raw) {
            raw = 0.65 * raw + 0.35 * symptomSignal;
        }

        Double prevSmoothed;
        boolean prevAlert;
        if (fresh) {
            prevSmoothed = null;
            prevAlert = false;
        } else {
            Optional<RiskScoreHistory> prior = riskHistoryRepo.findLatest(userId);
            prevSmoothed = prior.map(RiskScoreHistory::getRiskScoreSmoothed).orElse(null);
            prevAlert = prior.map(p -> Boolean.TRUE.equals(p.getAlertTriggered())).orElse(false);
        }

        RiskScoreResult result = riskScorer.smoothAndClassify(raw, prevSmoothed, prevAlert);

        int symptomDays = symptomRepo.countSymptomDays(userId);
        ConfidenceLevel confidence = ConfidenceLevel.fromSymptomDays(symptomDays);

        contributions.sort(Comparator.comparingDouble(FactorContribution::weight).reversed());

        return Optional.of(new RiskScoreBreakdown(
                Instant.now(), result, confidence, symptomDays, contributions, normalizedScores));
    }

    /**
     * Rebuilds a day-by-day risk score history so the dashboard trend chart has a real
     * time series instead of a single point computed "now".
     *
     * The live {@link #currentBreakdown} only ever scores the latest snapshot, so a fresh
     * demo would otherwise show a flat one-day chart. This walks the last {@code days}
     * days in chronological order, scores each against that day's snapshot using the same
     * learned model, and advances the EWMA/hysteresis chain across days — producing the
     * same smoothed signal the hourly scheduler would have built up over time. Existing
     * history is cleared first so re-seeding stays idempotent.
     *
     * @param userId    the user whose model and history series this builds
     * @param envUserId the owner of the environmental snapshots (same as userId in V1)
     * @param days      how many days back to backfill
     * @return number of daily score rows written
     */
    public int backfillHistory(String userId, String envUserId, int days) {
        List<CorrelationResult> model = correlationRepo.findByUserId(userId);
        if (model.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(HISTORY_DAYS));
        List<EnvironmentalSnapshot> history = snapshotRepo.findByDateRange(envUserId, from, now);
        if (history.isEmpty()) {
            return 0;
        }
        Map<Factor, List<Double>> historyByFactor = collectHistory(history);

        // One representative snapshot per day (highest PM2.5 — the same daily pick the
        // demo symptom generator uses, so symptoms and scores reference the same reading).
        Map<LocalDate, EnvironmentalSnapshot> byDay = new TreeMap<>();
        for (EnvironmentalSnapshot s : history) {
            LocalDate day = s.getRecordedAt().atZone(ZoneOffset.UTC).toLocalDate();
            byDay.merge(day, s, (a, b) ->
                    (a.getPm25() != null && b.getPm25() != null && a.getPm25() >= b.getPm25()) ? a : b);
        }

        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        int symptomDays = symptomRepo.countSymptomDays(userId);
        ConfidenceLevel confidence = ConfidenceLevel.fromSymptomDays(symptomDays);

        riskHistoryRepo.deleteByUserId(userId);

        Double prevSmoothed = null;
        boolean prevAlert = false;
        int written = 0;

        for (Map.Entry<LocalDate, EnvironmentalSnapshot> entry : byDay.entrySet()) {
            LocalDate day = entry.getKey();
            if (day.isBefore(cutoff)) continue;
            EnvironmentalSnapshot snap = entry.getValue();

            Map<String, Double> normalizedScores = new HashMap<>();
            Map<String, Double> scoreInputs = new HashMap<>();
            Map<String, Double> weights = new HashMap<>();
            List<Double> activeWeights = new ArrayList<>();
            for (CorrelationResult row : model) {
                Factor factor = Factor.fromKey(row.getFactorName());
                if (factor == null) continue;
                Double value = factor.extract(snap);
                if (value == null) continue;
                double percentile =
                        normalizer.normalize(value, historyByFactor.getOrDefault(factor, List.of()));
                boolean aboveThreshold = row.getPersonalThreshold() != null
                        && value >= row.getPersonalThreshold();
                double w = row.getWeight() != null ? row.getWeight() : 0.0;
                if (aboveThreshold) activeWeights.add(w);
                normalizedScores.put(factor.getKey(), percentile);
                scoreInputs.put(factor.getKey(), personalScoreInput(percentile, aboveThreshold));
                weights.put(factor.getKey(), w);
            }
            if (normalizedScores.isEmpty()) continue;

            // Count active triggers that carry at least an average share of weight.
            double avgWeight = 1.0 / normalizedScores.size();
            long meaningful = activeWeights.stream().filter(w -> w >= avgWeight).count();
            double raw = blendedRaw(scoreInputs, weights, meaningful);
            RiskScoreResult result = riskScorer.smoothAndClassify(raw, prevSmoothed, prevAlert);

            RiskScoreHistory rowToSave = new RiskScoreHistory();
            rowToSave.setScoredAt(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
            rowToSave.setUserId(userId);
            rowToSave.setRiskScore(result.rawScore());
            rowToSave.setRiskScoreSmoothed(result.smoothedScore());
            rowToSave.setRiskLabel(result.level().getLabel());
            rowToSave.setAlertTriggered(result.alertActive());
            rowToSave.setFactorScores(toJson(normalizedScores));
            rowToSave.setConfidenceLevel(confidence.name());
            rowToSave.setSymptomDaysAvailable(symptomDays);
            riskHistoryRepo.save(rowToSave);

            prevSmoothed = result.smoothedScore();
            prevAlert = result.alertActive();
            written++;
        }

        log.info("Backfilled {} daily risk scores for user '{}'", written, userId);
        return written;
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
     * Returns a 0-100 signal from the user's highest symptom severity in the last 3 days.
     * Zero when no recent logs exist, so the score falls back to the pure environmental model.
     */
    private double recentSymptomSignal(String userId) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(3));
        List<SymptomLog> recent = symptomRepo.findByDateRange(userId, cutoff, Instant.now());
        if (recent.isEmpty()) return 0;
        return recent.stream()
                .mapToInt(s -> s.getSeverity() != null ? s.getSeverity() : 0)
                .max().orElse(0) * 10.0;
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
                                     boolean aboveThreshold, double weight,
                                     Double value, String unit) {}

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
