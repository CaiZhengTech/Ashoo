package com.ashoo.correlation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MismatchDetector}.
 *
 * Confirms both disagreement directions are detected, agreeing days are ignored,
 * a missing severity entry is treated as a symptom-free day, and results are
 * ordered by discrepancy.
 */
class MismatchDetectorTest {

    private final MismatchDetector detector = new MismatchDetector();

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);
    private static final LocalDate D4 = LocalDate.of(2026, 1, 4);

    @Test
    void detectsFalseAlarm_highScoreNoSymptoms() {
        List<MismatchDay> result = detector.detect(
                Map.of(D1, 85.0),
                Map.of(D1, 0));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(MismatchDay.Type.HIGH_SCORE_NO_SYMPTOMS);
    }

    @Test
    void detectsMissedFlare_lowScoreSevereSymptoms() {
        List<MismatchDay> result = detector.detect(
                Map.of(D1, 30.0),
                Map.of(D1, 8));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(MismatchDay.Type.LOW_SCORE_SEVERE_SYMPTOMS);
    }

    @Test
    void agreeingDay_isNotAMismatch() {
        // High score AND severe symptoms = the model was right.
        List<MismatchDay> result = detector.detect(
                Map.of(D1, 85.0),
                Map.of(D1, 7));
        assertThat(result).isEmpty();
    }

    @Test
    void missingSeverity_treatedAsSymptomFree() {
        List<MismatchDay> result = detector.detect(
                Map.of(D1, 90.0),
                Map.of()); // no severity logged for D1
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(MismatchDay.Type.HIGH_SCORE_NO_SYMPTOMS);
    }

    @Test
    void resultsOrderedByDiscrepancyDescending() {
        List<MismatchDay> result = detector.detect(
                Map.of(
                        D1, 75.0,  // false alarm, discrepancy 75
                        D2, 95.0,  // false alarm, discrepancy 95 (biggest)
                        D3, 20.0,  // missed flare sev 8 → discrepancy 80
                        D4, 50.0), // agreeing-ish: score 50 not ≤40, sev 0 → not a mismatch
                Map.of(D1, 0, D2, 0, D3, 8, D4, 0));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).date()).isEqualTo(D2); // 95
        assertThat(result.get(1).date()).isEqualTo(D3); // 80
        assertThat(result.get(2).date()).isEqualTo(D1); // 75
    }
}
