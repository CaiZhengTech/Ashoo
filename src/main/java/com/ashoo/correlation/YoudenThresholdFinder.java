package com.ashoo.correlation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Finds the personal threshold for a factor — the value above which the user
 * tends to have symptoms — using Youden's J statistic.
 *
 * Youden's J = sensitivity + specificity − 1. We test every candidate cut-point in
 * the data and keep the one that maximizes J. This is the standard medical-statistics
 * method for choosing a diagnostic cut-point, repurposed here to learn a personal
 * environmental trigger level. J ranges 0 (no better than chance) to 1 (perfect
 * separation), so it doubles as a quality score for the threshold.
 *
 * Direction assumption: thresholds are computed as "symptoms when value ≥ cut-point,"
 * which fits the factors that actually drive Ashoo (PM2.5, pollen, humidity — higher
 * is worse). Protective factors are handled upstream by weighting on |Spearman|;
 * their threshold is informational only.
 *
 * Sparse-data fallback: with fewer than {@code minSymptomDays} symptom days, J is
 * unreliable (too few positives to estimate sensitivity). We then fall back to the
 * 75th percentile of the user's symptom-day readings — an honest "this is roughly
 * where your symptom days sit" estimate — and label it LOW confidence.
 */
@Component
public class YoudenThresholdFinder {

    /** Default minimum symptom days for Youden's J; mirrors {@code ashoo.correlation.min-symptom-days}. */
    public static final int DEFAULT_MIN_SYMPTOM_DAYS = 10;

    /**
     * Finds the personal threshold using the default minimum symptom-day count.
     *
     * @param symptomDayValues    factor readings on days with logged symptoms (severity ≥ 1)
     * @param nonSymptomDayValues factor readings on symptom-free days
     * @return the threshold, its Youden's J, and the confidence behind it
     */
    public ThresholdResult findThreshold(List<Double> symptomDayValues,
                                         List<Double> nonSymptomDayValues) {
        return findThreshold(symptomDayValues, nonSymptomDayValues, DEFAULT_MIN_SYMPTOM_DAYS);
    }

    /**
     * Finds the personal threshold that best separates symptom days from symptom-free days.
     *
     * @param symptomDayValues    factor readings on days with logged symptoms (severity ≥ 1)
     * @param nonSymptomDayValues factor readings on symptom-free days
     * @param minSymptomDays      below this many symptom days, use the sparse fallback
     * @return a {@link ThresholdResult} that always carries a confidence level and the
     *         number of symptom days used, so the UI can be transparent about reliability
     */
    public ThresholdResult findThreshold(List<Double> symptomDayValues,
                                         List<Double> nonSymptomDayValues,
                                         int minSymptomDays) {
        int symptomDays = symptomDayValues == null ? 0 : symptomDayValues.size();
        ConfidenceLevel confidence = ConfidenceLevel.fromSymptomDays(symptomDays);

        // No positives at all — nothing to learn from.
        if (symptomDays == 0) {
            return new ThresholdResult(Double.NaN, Double.NaN, confidence, 0, true);
        }

        boolean sparse = symptomDays < minSymptomDays
                || nonSymptomDayValues == null || nonSymptomDayValues.isEmpty();

        if (sparse) {
            double fallback = percentile75(symptomDayValues);
            return new ThresholdResult(fallback, Double.NaN, confidence, symptomDays, true);
        }

        // Enough data: search candidate cut-points for the one maximizing Youden's J.
        TreeSet<Double> candidates = new TreeSet<>();
        candidates.addAll(symptomDayValues);
        candidates.addAll(nonSymptomDayValues);

        double bestThreshold = candidates.first();
        double bestJ = -1.0;
        int positives = symptomDays;
        int negatives = nonSymptomDayValues.size();

        for (double t : candidates) {
            int tp = countAtLeast(symptomDayValues, t);
            int fp = countAtLeast(nonSymptomDayValues, t);
            double sensitivity = (double) tp / positives;          // TP / (TP + FN)
            double specificity = (double) (negatives - fp) / negatives; // TN / (TN + FP)
            double j = sensitivity + specificity - 1.0;
            if (j > bestJ) {
                bestJ = j;
                bestThreshold = t;
            }
        }

        return new ThresholdResult(bestThreshold, bestJ, confidence, symptomDays, false);
    }

    /**
     * Counts how many values are at or above a cut-point.
     *
     * @param values the readings to scan
     * @param t      the cut-point
     * @return number of values ≥ t
     */
    private static int countAtLeast(List<Double> values, double t) {
        int count = 0;
        for (double v : values) {
            if (v >= t) count++;
        }
        return count;
    }

    /**
     * Computes the 75th percentile of a sample using the nearest-rank method.
     *
     * Nearest-rank is used (rather than interpolation) because the fallback is an
     * approximation anyway under sparse data, and nearest-rank always returns an
     * actually-observed value, which is easier to explain to the user.
     *
     * @param values the sample (must be non-empty)
     * @return the value at the 75th percentile
     */
    private static double percentile75(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int rank = (int) Math.ceil(0.75 * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index);
    }
}
