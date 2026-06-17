package com.ashoo.correlation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes the point-biserial correlation between a continuous factor and a
 * binary outcome (symptom day vs. symptom-free day).
 *
 * Spearman measures how factor levels track symptom <em>severity</em>; point-biserial
 * answers a complementary, simpler question: does this factor distinguish the days
 * you had <em>any</em> symptoms from the days you had none? The two together give a
 * fuller picture — a factor can separate good days from bad days cleanly (high
 * point-biserial) even if it does not track the exact severity number (lower Spearman).
 *
 * Point-biserial is mathematically just Pearson's r with one variable coded 0/1, so
 * it lands in the same [−1, 1] range and is directly comparable to the Spearman value.
 */
@Component
public class PointBiserialCorrelator {

    /** Minimum aligned pairs below which the coefficient is unreliable. */
    public static final int MIN_PAIRS = SpearmanCorrelator.MIN_PAIRS;

    /**
     * Computes the point-biserial correlation coefficient.
     *
     * Uses the standard closed form:
     * <pre>  r_pb = (M1 − M0) / s · √(p · q)</pre>
     * where M1/M0 are the mean factor values on symptom/symptom-free days, s is the
     * population standard deviation of all factor values, and p/q are the proportions
     * of symptom/symptom-free days. This form makes the intuition explicit: the
     * coefficient grows with the gap between the two groups' means, scaled by how
     * balanced the two groups are.
     *
     * @param factorValues   the continuous factor readings
     * @param symptomPresent parallel flags: true if that day was a symptom day (severity ≥ 1)
     * @return r_pb in [−1, 1]; {@code Double.NaN} if the inputs are mismatched in length
     *         or have fewer than {@link #MIN_PAIRS} pairs; {@code 0.0} if every day is
     *         in the same class, or the factor has zero variance (no separation possible)
     */
    public double correlate(List<Double> factorValues, List<Boolean> symptomPresent) {
        if (factorValues == null || symptomPresent == null
                || factorValues.size() != symptomPresent.size()
                || factorValues.size() < MIN_PAIRS) {
            return Double.NaN;
        }

        int n = factorValues.size();
        double sum1 = 0, sum0 = 0;
        int n1 = 0, n0 = 0;
        double grandSum = 0;
        for (int i = 0; i < n; i++) {
            double v = factorValues.get(i);
            grandSum += v;
            if (symptomPresent.get(i)) { sum1 += v; n1++; }
            else                       { sum0 += v; n0++; }
        }

        // Need at least one day in each class to compare them
        if (n1 == 0 || n0 == 0) {
            return 0.0;
        }

        double mean = grandSum / n;
        double variance = 0;
        for (double v : factorValues) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= n; // population variance
        double std = Math.sqrt(variance);
        if (std == 0) {
            return 0.0;
        }

        double m1 = sum1 / n1;
        double m0 = sum0 / n0;
        double p = (double) n1 / n;
        double q = (double) n0 / n;

        return (m1 - m0) / std * Math.sqrt(p * q);
    }
}
