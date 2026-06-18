package com.ashoo.briefing;

import com.ashoo.briefing.BriefingContext.RecentSymptom;
import com.ashoo.briefing.BriefingContext.TopFactor;
import com.ashoo.common.AshooProperties;
import com.ashoo.correlation.ConfidenceLevel;
import com.ashoo.correlation.RiskScoringService;
import com.ashoo.correlation.RiskScoringService.RiskScoreBreakdown;
import com.ashoo.storage.entity.Medication;
import com.ashoo.storage.entity.SavedLocation;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.MedicationRepository;
import com.ashoo.storage.repository.SavedLocationRepository;
import com.ashoo.storage.repository.SymptomLogRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assembles the structured {@link BriefingContext} for a user.
 *
 * This is the boundary where database rows become the AI-safe allow-list. It pulls the
 * current score, the recent symptom history, and the registered medication <em>types</em>
 * — and deliberately drops everything else, most importantly the free-text symptom notes.
 * Because the output type has no field for notes, there is no path by which a note can
 * reach the external API.
 */
@Component
public class BriefingContextBuilder {

    private static final int RECENT_DAYS = 7;
    private static final int TOP_FACTOR_COUNT = 3;

    private final RiskScoringService riskScoringService;
    private final SymptomLogRepository symptomRepo;
    private final MedicationRepository medicationRepo;
    private final SavedLocationRepository locationRepo;
    private final AshooProperties props;

    public BriefingContextBuilder(RiskScoringService riskScoringService,
                                  SymptomLogRepository symptomRepo,
                                  MedicationRepository medicationRepo,
                                  SavedLocationRepository locationRepo,
                                  AshooProperties props) {
        this.riskScoringService = riskScoringService;
        this.symptomRepo = symptomRepo;
        this.medicationRepo = medicationRepo;
        this.locationRepo = locationRepo;
        this.props = props;
    }

    /**
     * Builds the briefing context for a user from their current model and recent history.
     *
     * @param userId the user to build for
     * @return a fully populated, privacy-safe context ready to serialize into the prompt
     */
    public BriefingContext buildContext(String userId) {
        // Environmental data lives under the shared env user in V1, so a persona's
        // briefing must score against that — not against its own (empty) env history.
        Optional<RiskScoreBreakdown> breakdownOpt =
                riskScoringService.currentBreakdown(userId, com.ashoo.common.DemoUsers.ENV_USER);

        int riskScore;
        String riskLabel;
        List<TopFactor> topFactors;
        String confidence;
        int symptomDays;

        if (breakdownOpt.isPresent()) {
            RiskScoreBreakdown b = breakdownOpt.get();
            riskScore = (int) Math.round(b.score().smoothedScore());
            riskLabel = b.score().level().getLabel();
            topFactors = b.factors().stream()
                    .limit(TOP_FACTOR_COUNT)
                    .map(f -> new TopFactor(f.displayName(), round1(f.percentile()), f.aboveThreshold()))
                    .toList();
            confidence = b.confidence().name();
            symptomDays = b.symptomDaysAvailable();
        } else {
            // No model yet (fresh user) — still produce an honest "keep logging" context.
            symptomDays = symptomRepo.countSymptomDays(userId);
            riskScore = 0;
            riskLabel = "Unknown";
            topFactors = List.of();
            confidence = ConfidenceLevel.fromSymptomDays(symptomDays).name();
        }

        Map<Long, String> medTypeById = medicationRepo.findActiveByUserId(userId).stream()
                .collect(Collectors.toMap(Medication::getId, Medication::getMedType));
        List<String> registeredTypes = new ArrayList<>(new LinkedHashSet<>(medTypeById.values()));

        List<RecentSymptom> recent = buildRecentSymptoms(userId, medTypeById);
        String location = resolveLocation(userId);

        return new BriefingContext(riskScore, riskLabel, topFactors, recent,
                registeredTypes, location, confidence, symptomDays);
    }

    /**
     * Reduces the last 7 days of symptom logs to non-identifying structured rows.
     *
     * Only severity, days-ago, and medication <em>types</em> survive — the free-text note
     * is intentionally never read here.
     */
    private List<RecentSymptom> buildRecentSymptoms(String userId, Map<Long, String> medTypeById) {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(RECENT_DAYS));
        List<RecentSymptom> recent = new ArrayList<>();
        for (SymptomLog log : symptomRepo.findByDateRange(userId, from, now)) {
            long daysAgo = ChronoUnit.DAYS.between(log.getLoggedAt(), now);
            List<String> types = new ArrayList<>();
            if (log.getMedicationsUsed() != null) {
                LinkedHashSet<String> distinct = new LinkedHashSet<>();
                for (Long medId : log.getMedicationsUsed()) {
                    String type = medTypeById.get(medId);
                    if (type != null) distinct.add(type);
                }
                types.addAll(distinct);
            }
            int severity = log.getSeverity() != null ? log.getSeverity() : 0;
            recent.add(new RecentSymptom((int) daysAgo, severity, types));
        }
        return recent;
    }

    /**
     * Resolves the user's primary location name, falling back to the configured default.
     */
    private String resolveLocation(String userId) {
        List<SavedLocation> locations = locationRepo.findActiveByUserId(userId);
        return locations.stream()
                .filter(l -> Boolean.TRUE.equals(l.getIsPrimary()))
                .map(SavedLocation::getCityName)
                .findFirst()
                .or(() -> locations.stream().map(SavedLocation::getCityName).findFirst())
                .orElse(props.defaultLocation().city());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
