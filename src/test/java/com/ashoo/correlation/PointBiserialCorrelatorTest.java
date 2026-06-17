package com.ashoo.correlation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PointBiserialCorrelator}.
 *
 * Confirms the NaN insufficient-data signal, the single-class guard, sign
 * correctness (symptom days with higher readings → positive), and that the
 * coefficient stays within [−1, 1].
 */
class PointBiserialCorrelatorTest {

    private final PointBiserialCorrelator correlator = new PointBiserialCorrelator();

    private static final List<Double> LOW_THEN_HIGH = List.of(
            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0);
    private static final List<Boolean> SIX_FALSE_SIX_TRUE = List.of(
            false, false, false, false, false, false, true, true, true, true, true, true);

    @Test
    void insufficientPairs_returnsNaN() {
        assertThat(correlator.correlate(
                List.of(1.0, 2.0, 3.0),
                List.of(true, false, true))).isNaN();
    }

    @Test
    void mismatchedLengths_returnsNaN() {
        assertThat(correlator.correlate(LOW_THEN_HIGH, SIX_FALSE_SIX_TRUE.subList(0, 11))).isNaN();
    }

    @Test
    void allSameClass_returnsZero() {
        List<Boolean> allFalse = java.util.Collections.nCopies(12, false);
        assertThat(correlator.correlate(LOW_THEN_HIGH, allFalse)).isEqualTo(0.0);
    }

    @Test
    void symptomDaysHaveHigherReadings_returnsStrongPositive() {
        double rpb = correlator.correlate(LOW_THEN_HIGH, SIX_FALSE_SIX_TRUE);
        assertThat(rpb).isGreaterThan(0.8).isLessThanOrEqualTo(1.0);
    }

    @Test
    void symptomDaysHaveLowerReadings_returnsNegative() {
        List<Boolean> sixTrueSixFalse = List.of(
                true, true, true, true, true, true, false, false, false, false, false, false);
        double rpb = correlator.correlate(LOW_THEN_HIGH, sixTrueSixFalse);
        assertThat(rpb).isLessThan(0.0).isGreaterThanOrEqualTo(-1.0);
    }

    @Test
    void constantFactor_returnsZero() {
        List<Double> constant = java.util.Collections.nCopies(12, 5.0);
        assertThat(correlator.correlate(constant, SIX_FALSE_SIX_TRUE)).isEqualTo(0.0);
    }
}
