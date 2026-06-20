package com.ashoo.correlation;

/**
 * The result of finding a personal threshold for one factor.
 *
 * Bundles the cut-point with the evidence behind it so the UI can be honest:
 * "Threshold estimated from 6 symptom days (low confidence, keep logging)".
 * A record is ideal here — this is immutable value data with no behavior.
 *
 * @param threshold        the factor value (in native units) above which the user
 *                         tends to have symptoms; {@code NaN} if it could not be computed
 * @param youdenJ          the Youden's J statistic (sensitivity + specificity − 1) at the
 *                         chosen threshold, range 0–1; {@code NaN} when the sparse fallback
 *                         was used instead of Youden's method
 * @param confidence       how much to trust this threshold, derived from symptom-day count
 * @param symptomDaysUsed  number of symptom days the threshold was computed from
 * @param usedFallback     true if the 75th-percentile sparse fallback was used because
 *                         there were too few symptom days for Youden's J
 */
public record ThresholdResult(
        double threshold,
        double youdenJ,
        ConfidenceLevel confidence,
        int symptomDaysUsed,
        boolean usedFallback
) {}
