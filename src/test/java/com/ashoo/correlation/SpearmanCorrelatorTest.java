package com.ashoo.correlation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SpearmanCorrelator}.
 *
 * Verifies the NaN insufficient-data signal, the ±1 extremes for perfectly
 * monotonic relationships, the zero-variance guard, and robustness to a
 * non-linear-but-monotonic relationship (where Spearman beats Pearson).
 */
class SpearmanCorrelatorTest {

    private final SpearmanCorrelator correlator = new SpearmanCorrelator();

    /** Builds 0,1,2,... as doubles. */
    private static List<Double> ramp(int n) {
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add((double) i);
        return list;
    }

    @Test
    void insufficientPairs_returnsNaN() {
        assertThat(correlator.correlate(ramp(9), ramp(9))).isNaN();
    }

    @Test
    void mismatchedLengths_returnsNaN() {
        assertThat(correlator.correlate(ramp(12), ramp(11))).isNaN();
    }

    @Test
    void perfectMonotonicIncrease_returnsOne() {
        assertThat(correlator.correlate(ramp(12), ramp(12))).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void perfectMonotonicDecrease_returnsMinusOne() {
        List<Double> x = ramp(12);
        List<Double> y = new ArrayList<>(x);
        java.util.Collections.reverse(y);
        assertThat(correlator.correlate(x, y)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void constantSeries_returnsZero() {
        List<Double> x = ramp(12);
        List<Double> constant = new ArrayList<>();
        for (int i = 0; i < 12; i++) constant.add(7.0);
        assertThat(correlator.correlate(x, constant)).isEqualTo(0.0);
    }

    @Test
    void nonLinearMonotonic_stillReturnsOne() {
        // y = x^3 is monotonic but very non-linear; Pearson would be < 1, Spearman is exactly 1.
        List<Double> x = ramp(12);
        List<Double> yCubed = new ArrayList<>();
        for (double v : x) yCubed.add(v * v * v);
        assertThat(correlator.correlate(x, yCubed)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void tiedValues_handledByAverageRank() {
        // x has a tie; result should be a valid finite correlation, not NaN.
        List<Double> x = List.of(1.0, 2.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0);
        List<Double> y = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0);
        double rho = correlator.correlate(x, y);
        assertThat(rho).isGreaterThan(0.9).isLessThanOrEqualTo(1.0);
    }
}
