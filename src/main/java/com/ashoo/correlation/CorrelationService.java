package com.ashoo.correlation;

import com.ashoo.common.AshooProperties;
import com.ashoo.storage.entity.CorrelationResult;
import com.ashoo.storage.entity.EnvironmentalSnapshot;
import com.ashoo.storage.entity.RecalibrationEvent;
import com.ashoo.storage.entity.SymptomLog;
import com.ashoo.storage.repository.CorrelationResultRepository;
import com.ashoo.storage.repository.EnvironmentalSnapshotRepository;
import com.ashoo.storage.repository.RecalibrationEventRepository;
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
 * Runs the on-demand correlation pipeline: align a user's environmental history
 * with their symptom log, learn which factors matter, and cache the result.
 *
 * This is the orchestration layer. The actual statistics live in stateless helper
 * components ({@link SpearmanCorrelator}, {@link YoudenThresholdFinder}, etc.) that
 * know nothing about the database; this service does the data wrangling — daily
 * aggregation, lag alignment, weight normalization, persistence — and delegates
 * every numeric decision to those tested helpers.
 *
 * Environmental and symptom data are addressed by separate user ids on purpose.
 * In the single-user V1 they are the same, but demo personas share one set of real
 * Amsterdam readings while each has their own synthetic symptom log — so the engine
 * must be able to join symptoms for user A against environment owned by user B.
 */
@Service
public class CorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);

    /** How far back to pull history. Matches the 6-month raw-snapshot retention window. */
    private static final int LOOKBACK_DAYS = 180;

    private final EnvironmentalSnapshotRepository snapshotRepo;
    private final SymptomLogRepository symptomRepo;
    private final CorrelationResultRepository correlationRepo;
    private final RecalibrationEventRepository recalibrationRepo;
    private final PercentileNormalizer normalizer;
    private final SpearmanCorrelator spearman;
    private final PointBiserialCorrelator pointBiserial;
    private final YoudenThresholdFinder thresholdFinder;
    private final MismatchDetector mismatchDetector;
    private final AshooProperties props;

    public CorrelationService(EnvironmentalSnapshotRepository snapshotRepo,
                              SymptomLogRepository symptomRepo,
                              CorrelationResultRepository correlationRepo,
                              RecalibrationEventRepository recalibrationRepo,
                              PercentileNormalizer normalizer,
                              SpearmanCorrelator spearman,
                              PointBiserialCorrelator pointBiserial,
                              YoudenThresholdFinder thresholdFinder,
                              MismatchDetector mismatchDetector,
                              AshooProperties props) {
        this.snapshotRepo = snapshotRepo;
        this.symptomRepo = symptomRepo;
        this.correlationRepo = correlationRepo;
        this.recalibrationRepo = recalibrationRepo;
        this.normalizer = normalizer;
        this.spearman = spearman;
        this.pointBiserial = pointBiserial;
        this.thresholdFinder = thresholdFinder;
        this.mismatchDetector = mismatchDetector;
        this.props = props;
    }

    /**
     * Recomputes correlations for a single user whose symptoms and environment
     * share the same id (the normal V1 path).
     *
     * @param userId the user to recompute for
     * @return a summary of what the run produced
     */
    public CorrelationSummary compute(String userId) {
        return computeAndStore(userId, userId);
    }

    /**
     * Recomputes correlations and replaces the user's cached results.
     *
     * Steps: pull the lookback window, aggregate environment to daily values, learn
     * each factor's best lag / threshold / weight, normalize weights to sum to 1, count
     * model mismatches, persist the rows, and log a recalibration event for transparency.
     *
     * @param symptomUserId the user whose symptom log supplies the labels
     * @param envUserId     the user whose environmental snapshots supply the features
     * @return a summary of the run (factors kept, confidence, timing)
     */
    public CorrelationSummary computeAndStore(String symptomUserId, String envUserId) {
        long start = System.currentTimeMillis();
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(LOOKBACK_DAYS));

        List<EnvironmentalSnapshot> snapshots = snapshotRepo.findByDateRange(envUserId, from, to);
        List<SymptomLog> symptoms = symptomRepo.findByDateRange(symptomUserId, from, to);

        Map<Factor, Map<LocalDate, Double>> dailyFactors = aggregateDaily(snapshots);
        Map<LocalDate, Integer> severityByDay = maxSeverityByDay(symptoms);
        List<LocalDate> envDays = collectEnvDays(dailyFactors);
        int totalSymptomDays = (int) severityByDay.values().stream().filter(s -> s >= 1).count();
        ConfidenceLevel confidence = ConfidenceLevel.fromSymptomDays(totalSymptomDays);
        int minSymptomDays = props.correlation().minSymptomDays();
        List<Integer> lagWindows = props.correlation().lagWindowsHours();

        // 1. Compute per-factor statistics
        List<FactorComputation> computations = new ArrayList<>();
        for (Factor factor : Factor.values()) {
            Map<LocalDate, Double> dayValues = dailyFactors.get(factor);
            if (dayValues == null || dayValues.isEmpty()) continue;

            FactorComputation fc = computeFactor(
                    factor, dayValues, severityByDay, envDays, lagWindows, minSymptomDays);
            if (fc != null) computations.add(fc);
        }

        // 2. Normalize weights to sum to 1.0 across the factors that survived
        double weightSum = computations.stream().mapToDouble(FactorComputation::weightRaw).sum();

        // 3. Reconstruct daily scores with the final weights and count mismatches
        Map<String, Double> weightByKey = new HashMap<>();
        for (FactorComputation fc : computations) {
            double w = weightSum > 0 ? fc.weightRaw() / weightSum : 0.0;
            weightByKey.put(fc.factor().getKey(), w);
        }
        Map<LocalDate, Double> reconstructed = reconstructDailyScores(dailyFactors, weightByKey, envDays);
        int mismatchCount = mismatchDetector.detect(reconstructed, severityByDay).size();

        // 4. Replace the cached rows
        correlationRepo.deleteByUserId(symptomUserId);
        Instant computedAt = Instant.now();
        for (FactorComputation fc : computations) {
            double weight = weightSum > 0 ? fc.weightRaw() / weightSum : 0.0;
            correlationRepo.save(toRow(fc, symptomUserId, computedAt, weight,
                    confidence, totalSymptomDays, envDays.size(), mismatchCount));
        }

        // 5. Record the recalibration for transparency
        long elapsed = System.currentTimeMillis() - start;
        logRecalibration(symptomUserId, elapsed);

        log.info("Correlation recompute for {}: {} factors, {} symptom days, {} mismatches, {} ms",
                symptomUserId, computations.size(), totalSymptomDays, mismatchCount, elapsed);

        return new CorrelationSummary(
                computations.size(), totalSymptomDays, envDays.size(),
                confidence, mismatchCount, elapsed);
    }

    /**
     * Returns the user's cached correlation rows, strongest weight first.
     *
     * Read-only accessor for the results endpoint — the heavy recompute happens in
     * {@link #computeAndStore}, and this just hands back what it stored.
     *
     * @param userId the user identifier
     * @return the cached correlation rows
     */
    public List<CorrelationResult> findResults(String userId) {
        return correlationRepo.findByUserId(userId);
    }

    /**
     * Re-derives the user's mismatch days on demand using their cached weights.
     *
     * Rather than relying on months of accumulated live scores, we re-run the current
     * model over historical conditions and compare each day to what was logged. This
     * makes the mismatch view useful immediately after a recompute, not only after the
     * system has been scoring for weeks.
     *
     * @param symptomUserId the user whose symptoms supply the labels
     * @param envUserId     the user whose environment supplies the features
     * @return mismatch days ordered by how surprising they are
     */
    public List<MismatchDay> findMismatchDays(String symptomUserId, String envUserId) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(LOOKBACK_DAYS));

        List<EnvironmentalSnapshot> snapshots = snapshotRepo.findByDateRange(envUserId, from, to);
        List<SymptomLog> symptoms = symptomRepo.findByDateRange(symptomUserId, from, to);

        Map<Factor, Map<LocalDate, Double>> dailyFactors = aggregateDaily(snapshots);
        Map<LocalDate, Integer> severityByDay = maxSeverityByDay(symptoms);
        List<LocalDate> envDays = collectEnvDays(dailyFactors);

        Map<String, Double> weightByKey = new HashMap<>();
        for (CorrelationResult r : correlationRepo.findByUserId(symptomUserId)) {
            weightByKey.put(r.getFactorName(), r.getWeight() != null ? r.getWeight() : 0.0);
        }

        Map<LocalDate, Double> reconstructed = reconstructDailyScores(dailyFactors, weightByKey, envDays);
        return mismatchDetector.detect(reconstructed, severityByDay);
    }

    // ── Per-factor statistics ────────────────────────────────────────────────

    /**
     * Computes one factor's best-lag Spearman, point-biserial, threshold, and raw weight.
     *
     * Tests each configured lag and keeps the one with the strongest |Spearman|, since
     * symptoms may follow exposure by hours or days. Returns null when the factor has
     * too little aligned data to produce a real correlation at any lag.
     *
     * @return the factor computation, or null if the factor should be dropped from the model
     */
    private FactorComputation computeFactor(Factor factor,
                                            Map<LocalDate, Double> dayValues,
                                            Map<LocalDate, Integer> severityByDay,
                                            List<LocalDate> envDays,
                                            List<Integer> lagWindows,
                                            int minSymptomDays) {
        int bestLagHours = -1;
        double bestRho = 0.0;
        double bestAbsRho = -1.0;
        AlignedData bestAligned = null;

        for (int lagHours : lagWindows) {
            AlignedData aligned = align(dayValues, severityByDay, envDays, lagHours / 24);
            double rho = spearman.correlate(aligned.factorValues(), aligned.severities());
            if (Double.isNaN(rho)) continue;
            if (Math.abs(rho) > bestAbsRho) {
                bestAbsRho = Math.abs(rho);
                bestRho = rho;
                bestLagHours = lagHours;
                bestAligned = aligned;
            }
        }

        if (bestAligned == null) return null; // no lag had enough aligned data

        double rpb = pointBiserial.correlate(bestAligned.factorValues(), bestAligned.symptomPresent());

        List<Double> symptomDayValues = new ArrayList<>();
        List<Double> nonSymptomDayValues = new ArrayList<>();
        List<Double> fv = bestAligned.factorValues();
        List<Boolean> present = bestAligned.symptomPresent();
        for (int i = 0; i < fv.size(); i++) {
            if (present.get(i)) symptomDayValues.add(fv.get(i));
            else nonSymptomDayValues.add(fv.get(i));
        }

        ThresholdResult threshold = thresholdFinder.findThreshold(
                symptomDayValues, nonSymptomDayValues, minSymptomDays);

        List<Double> allValues = new ArrayList<>(dayValues.values());
        double thresholdPercentile = Double.isNaN(threshold.threshold())
                ? Double.NaN
                : normalizer.normalize(threshold.threshold(), allValues);

        return new FactorComputation(
                factor, bestLagHours, bestRho, rpb, threshold,
                thresholdPercentile, Math.abs(bestRho));
    }

    /**
     * Builds the aligned (feature, outcome) pairs for one factor at one lag.
     *
     * For each outcome day, the feature is the factor value {@code lagDays} earlier;
     * pairs are only kept when that earlier reading exists, so gaps in the data don't
     * silently shift the alignment.
     */
    private AlignedData align(Map<LocalDate, Double> dayValues,
                              Map<LocalDate, Integer> severityByDay,
                              List<LocalDate> envDays,
                              int lagDays) {
        List<Double> factorValues = new ArrayList<>();
        List<Double> severities = new ArrayList<>();
        List<Boolean> symptomPresent = new ArrayList<>();
        for (LocalDate day : envDays) {
            Double fv = dayValues.get(day.minusDays(lagDays));
            if (fv == null) continue;
            int severity = severityByDay.getOrDefault(day, 0);
            factorValues.add(fv);
            severities.add((double) severity);
            symptomPresent.add(severity >= 1);
        }
        return new AlignedData(factorValues, severities, symptomPresent);
    }

    // ── Daily reconstruction (for mismatch detection) ────────────────────────

    /**
     * Reconstructs a per-day risk score from current weights, with no lag and no EWMA.
     *
     * This is the "what would the model have said that day" view used for mismatch
     * detection. Each factor value is percentile-normalized against its own history,
     * then combined by weight (renormalized over the factors present that day).
     */
    private Map<LocalDate, Double> reconstructDailyScores(Map<Factor, Map<LocalDate, Double>> dailyFactors,
                                                          Map<String, Double> weightByKey,
                                                          List<LocalDate> envDays) {
        Map<Factor, List<Double>> historyByFactor = new EnumMap<>(Factor.class);
        for (Map.Entry<Factor, Map<LocalDate, Double>> e : dailyFactors.entrySet()) {
            historyByFactor.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        }

        Map<LocalDate, Double> scoreByDay = new HashMap<>();
        for (LocalDate day : envDays) {
            double weightedSum = 0.0;
            double weightTotal = 0.0;
            for (Map.Entry<Factor, Map<LocalDate, Double>> e : dailyFactors.entrySet()) {
                Double value = e.getValue().get(day);
                if (value == null) continue;
                Double w = weightByKey.get(e.getKey().getKey());
                if (w == null || w == 0.0) continue;
                double norm = normalizer.normalize(value, historyByFactor.get(e.getKey()));
                weightedSum += w * norm;
                weightTotal += w;
            }
            scoreByDay.put(day, weightTotal > 0 ? weightedSum / weightTotal : 0.0);
        }
        return scoreByDay;
    }

    // ── Aggregation helpers ──────────────────────────────────────────────────

    /**
     * Aggregates hourly snapshots into one average value per factor per day (UTC).
     *
     * Averaging only non-null readings means a factor missing for part of a day still
     * contributes its available hours rather than being dragged toward zero.
     */
    private Map<Factor, Map<LocalDate, Double>> aggregateDaily(List<EnvironmentalSnapshot> snapshots) {
        Map<Factor, Map<LocalDate, double[]>> acc = new EnumMap<>(Factor.class);
        for (Factor f : Factor.values()) acc.put(f, new HashMap<>());

        for (EnvironmentalSnapshot s : snapshots) {
            LocalDate day = s.getRecordedAt().atZone(ZoneOffset.UTC).toLocalDate();
            for (Factor f : Factor.values()) {
                Double v = f.extract(s);
                if (v == null) continue;
                double[] sumCount = acc.get(f).computeIfAbsent(day, d -> new double[2]);
                sumCount[0] += v;
                sumCount[1] += 1;
            }
        }

        Map<Factor, Map<LocalDate, Double>> result = new EnumMap<>(Factor.class);
        for (Factor f : Factor.values()) {
            Map<LocalDate, Double> averages = new HashMap<>();
            for (Map.Entry<LocalDate, double[]> e : acc.get(f).entrySet()) {
                averages.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
            }
            if (!averages.isEmpty()) result.put(f, averages);
        }
        return result;
    }

    /**
     * Reduces symptom logs to the worst severity logged on each day.
     */
    private Map<LocalDate, Integer> maxSeverityByDay(List<SymptomLog> symptoms) {
        Map<LocalDate, Integer> byDay = new HashMap<>();
        for (SymptomLog s : symptoms) {
            LocalDate day = s.getLoggedAt().atZone(ZoneOffset.UTC).toLocalDate();
            int severity = s.getSeverity() != null ? s.getSeverity() : 0;
            byDay.merge(day, severity, Math::max);
        }
        return byDay;
    }

    /**
     * Collects the sorted set of days that have any environmental data — the outcome axis.
     */
    private List<LocalDate> collectEnvDays(Map<Factor, Map<LocalDate, Double>> dailyFactors) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (Map<LocalDate, Double> m : dailyFactors.values()) {
            days.addAll(m.keySet());
        }
        return new ArrayList<>(days);
    }

    private CorrelationResult toRow(FactorComputation fc, String userId, Instant computedAt,
                                    double weight, ConfidenceLevel confidence,
                                    int symptomDays, int totalDays, int mismatchCount) {
        CorrelationResult r = new CorrelationResult();
        r.setUserId(userId);
        r.setComputedAt(computedAt);
        r.setFactorName(fc.factor().getKey());
        r.setBestLagHours(fc.bestLagHours());
        r.setSpearmanR(nanToNull(fc.spearman()));
        r.setPointBiserialR(nanToNull(fc.pointBiserial()));
        r.setPersonalThreshold(nanToNull(fc.threshold().threshold()));
        r.setThresholdPercentile(nanToNull(fc.thresholdPercentile()));
        r.setWeight(weight);
        r.setConfidenceLevel(confidence.name());
        r.setSymptomDaysUsed(symptomDays);
        r.setTotalDaysUsed(totalDays);
        r.setMismatchCount(mismatchCount);
        return r;
    }

    private void logRecalibration(String userId, long elapsedMs) {
        RecalibrationEvent event = new RecalibrationEvent();
        event.setUserId(userId);
        event.setTriggeredAt(Instant.now());
        event.setReason("correlation recomputed");
        event.setRecomputationMs((int) elapsedMs);
        recalibrationRepo.save(event);
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }

    // ── Internal value types ─────────────────────────────────────────────────

    /** The aligned feature/outcome arrays for one factor at one lag. */
    private record AlignedData(List<Double> factorValues, List<Double> severities,
                               List<Boolean> symptomPresent) {}

    /** A factor's computed statistics before weight normalization. */
    private record FactorComputation(Factor factor, int bestLagHours, double spearman,
                                     double pointBiserial, ThresholdResult threshold,
                                     double thresholdPercentile, double weightRaw) {}

    /**
     * Summary of one correlation run, returned to the API.
     *
     * @param factorsComputed how many factors survived into the model
     * @param symptomDaysUsed symptom days available in the window
     * @param totalDaysUsed   days of environmental data in the window
     * @param confidence      overall confidence from the symptom-day count
     * @param mismatchCount   number of model/reality disagreements found
     * @param elapsedMs       wall-clock time of the recompute
     */
    public record CorrelationSummary(int factorsComputed, int symptomDaysUsed, int totalDaysUsed,
                                     ConfidenceLevel confidence, int mismatchCount, long elapsedMs) {}
}
