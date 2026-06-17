package com.ashoo.correlation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes Spearman's rank correlation between a factor and symptom severity.
 *
 * Why Spearman, not Pearson? Pearson assumes both variables are roughly normal and
 * measures only <em>linear</em> association. Environmental and symptom data are
 * skewed (many calm days, few bad ones) and their relationship is often monotonic
 * but curved (symptoms ramp up sharply past a threshold). Spearman sidesteps both
 * problems by correlating the <em>ranks</em> of the values, making it robust to
 * skew and to outliers — the right default for health and environmental data.
 *
 * The implementation is Pearson's formula applied to average-tie ranks, which is
 * the textbook way to compute Spearman correctly when ties are present (the simpler
 * "6·Σd² / n(n²−1)" shortcut is only valid with no ties).
 */
@Component
public class SpearmanCorrelator {

    /** Minimum aligned pairs below which any correlation is statistical noise. */
    public static final int MIN_PAIRS = 10;

    /**
     * Computes Spearman's rho between two equal-length series.
     *
     * @param x first series (e.g. daily factor values)
     * @param y second series (e.g. daily symptom severities), aligned index-for-index with x
     * @return Spearman's rho in [−1, 1]; {@code Double.NaN} if the series differ in
     *         length or have fewer than {@link #MIN_PAIRS} pairs (signalling
     *         "not enough data to trust"); {@code 0.0} if either series is constant
     *         (no variance, so no correlation is defined)
     */
    public double correlate(List<Double> x, List<Double> y) {
        if (x == null || y == null || x.size() != y.size() || x.size() < MIN_PAIRS) {
            return Double.NaN;
        }
        double[] rankX = averageRanks(x);
        double[] rankY = averageRanks(y);
        return pearson(rankX, rankY);
    }

    /**
     * Assigns ranks to values, averaging the ranks of tied values.
     *
     * Example: values [10, 20, 20, 30] get ranks [1, 2.5, 2.5, 4] — the two tied
     * 20s share the average of positions 2 and 3. Averaging ties is what keeps the
     * downstream Pearson-on-ranks identity valid.
     *
     * @param values the series to rank
     * @return an array of ranks, parallel to the input order
     */
    private static double[] averageRanks(List<Double> values) {
        int n = values.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        idx = java.util.Arrays.stream(idx)
                .sorted((a, b) -> Double.compare(values.get(a), values.get(b)))
                .toArray(Integer[]::new);

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            // Advance j over a run of equal values
            while (j + 1 < n && values.get(idx[j + 1]).doubleValue() == values.get(idx[i]).doubleValue()) {
                j++;
            }
            // Average rank for positions i..j (1-based ranks)
            double avgRank = ((i + 1) + (j + 1)) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    /**
     * Standard Pearson correlation coefficient between two equal-length arrays.
     *
     * @param a first array
     * @param b second array
     * @return Pearson's r, or 0.0 if either array has zero variance
     */
    private static double pearson(double[] a, double[] b) {
        int n = a.length;
        double meanA = 0, meanB = 0;
        for (int i = 0; i < n; i++) { meanA += a[i]; meanB += b[i]; }
        meanA /= n; meanB /= n;

        double cov = 0, varA = 0, varB = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            cov += da * db;
            varA += da * da;
            varB += db * db;
        }
        if (varA == 0 || varB == 0) {
            return 0.0;
        }
        return cov / Math.sqrt(varA * varB);
    }
}
