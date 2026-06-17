package com.ashoo.correlation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts a raw environmental measurement into a personalized 0–100 score by
 * computing its percentile rank within the user's own historical distribution.
 *
 * Why percentile rank over min–max normalization? Min–max is wrecked by outliers:
 * a single wildfire day with PM2.5 of 300 squashes every normal day toward zero,
 * making day-to-day scores meaningless. Percentile rank is robust — "today is at
 * the 87th percentile of what YOU have seen" stays meaningful regardless of
 * outliers, and it works identically across factors with wildly different units
 * (µg/m³, grains/m³, hPa) with no conversion factor.
 *
 * A {@code @Component} with no state, so it is trivially unit-testable with
 * {@code new PercentileNormalizer()} and injectable wherever scoring happens.
 */
@Component
public class PercentileNormalizer {

    /**
     * Computes the percentile rank (0–100) of a value within a historical sample.
     *
     * Uses the "mid-rank" definition, which counts values strictly below plus half
     * the values exactly equal:
     * <pre>  percentile = (countBelow + 0.5 * countEqual) / N * 100</pre>
     * Splitting the ties in half makes the result symmetric — a value equal to every
     * historical reading lands at exactly 50, not 0 or 100, which is the honest answer
     * for "today is completely typical." This handles the all-same-values edge case
     * naturally without a special branch.
     *
     * @param value            the raw measurement in its native units
     * @param historicalValues all past raw values for this factor for this user;
     *                         may be empty
     * @return percentile rank in [0, 100]; returns 50.0 (neutral) when history is
     *         empty, since with no reference distribution we cannot claim the value
     *         is high or low
     */
    public double normalize(double value, List<Double> historicalValues) {
        if (historicalValues == null || historicalValues.isEmpty()) {
            return 50.0;
        }

        int countBelow = 0;
        int countEqual = 0;
        for (double h : historicalValues) {
            if (h < value) {
                countBelow++;
            } else if (h == value) {
                countEqual++;
            }
        }

        double rank = (countBelow + 0.5 * countEqual) / historicalValues.size();
        return rank * 100.0;
    }
}
