package com.ashoo.correlation;

import com.ashoo.common.AshooProperties;
import com.ashoo.common.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link RiskScorer}.
 *
 * Covers the weighted sum, renormalization over present factors, EWMA smoothing,
 * and the hysteresis state machine (on at 70, off at 55).
 */
class RiskScorerTest {

    /** Builds an AshooProperties carrying only the correlation tuning RiskScorer reads. */
    private static AshooProperties propsWith(double lambda, double on, double off) {
        AshooProperties.CorrelationConfig c = new AshooProperties.CorrelationConfig(
                10, List.of(0, 24, 48, 72), lambda, on, off);
        return new AshooProperties(null, null, null, null, null, null, c, null);
    }

    private final RiskScorer scorer = new RiskScorer(propsWith(0.3, 70.0, 55.0));

    @Test
    void weightedSum_firstRun_smoothedEqualsRaw() {
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 100.0, "humidity_pct", 0.0),
                Map.of("pm25", 0.5, "humidity_pct", 0.5),
                null, false);

        assertThat(r.rawScore()).isCloseTo(50.0, within(1e-9));
        assertThat(r.smoothedScore()).isCloseTo(50.0, within(1e-9)); // seeded to raw on first run
        assertThat(r.level()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(r.alertActive()).isFalse();
    }

    @Test
    void missingFactor_isRenormalizedNotDeflated() {
        // humidity has weight but no current reading → drop it, rescale pm25 to full weight.
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 80.0),
                Map.of("pm25", 0.3, "humidity_pct", 0.7),
                null, false);

        assertThat(r.rawScore()).isCloseTo(80.0, within(1e-9));
    }

    @Test
    void ewma_blendsThirtyPercentOfNewReading() {
        // smoothed = 0.3*100 + 0.7*0 = 30
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 100.0),
                Map.of("pm25", 1.0),
                0.0, false);

        assertThat(r.smoothedScore()).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void hysteresis_turnsOnAtSeventy() {
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 75.0), Map.of("pm25", 1.0), null, false);
        assertThat(r.alertActive()).isTrue();
    }

    @Test
    void hysteresis_doesNotTurnOnInDeadband() {
        // 65 is above the off-threshold but below the on-threshold → should NOT fire from off.
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 65.0), Map.of("pm25", 1.0), null, false);
        assertThat(r.alertActive()).isFalse();
    }

    @Test
    void hysteresis_staysOnUntilBelowOffThreshold() {
        RiskScoreResult stillOn = scorer.computeScore(
                Map.of("pm25", 60.0), Map.of("pm25", 1.0), null, true);
        assertThat(stillOn.alertActive()).isTrue(); // 60 ≥ 55, stays on

        RiskScoreResult turnsOff = scorer.computeScore(
                Map.of("pm25", 50.0), Map.of("pm25", 1.0), null, true);
        assertThat(turnsOff.alertActive()).isFalse(); // 50 < 55, clears
    }

    @Test
    void noUsableWeights_scoresZero() {
        RiskScoreResult r = scorer.computeScore(
                Map.of("pm25", 90.0),
                Map.of("pm25", 0.0),
                null, false);
        assertThat(r.rawScore()).isZero();
        assertThat(r.level()).isEqualTo(RiskLevel.GREAT);
    }
}
