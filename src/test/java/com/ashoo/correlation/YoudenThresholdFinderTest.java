package com.ashoo.correlation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link YoudenThresholdFinder}.
 *
 * Verifies the Youden's J formula on perfectly separable data, the sparse
 * 75th-percentile fallback, the empty-input guard, and confidence labelling.
 */
class YoudenThresholdFinderTest {

    private final YoudenThresholdFinder finder = new YoudenThresholdFinder();

    @Test
    void perfectlySeparable_givesJequalsOne() {
        // Symptom days all ≥ 20; symptom-free days all ≤ 12 → a clean split exists.
        List<Double> symptom = new ArrayList<>();
        for (int i = 0; i < 12; i++) symptom.add(20.0 + i);
        List<Double> nonSymptom = new ArrayList<>();
        for (int i = 1; i <= 12; i++) nonSymptom.add((double) i);

        ThresholdResult r = finder.findThreshold(symptom, nonSymptom);

        assertThat(r.usedFallback()).isFalse();
        assertThat(r.youdenJ()).isCloseTo(1.0, within(1e-9));
        assertThat(r.threshold()).isEqualTo(20.0); // lowest symptom value cleanly separates
        assertThat(r.confidence()).isEqualTo(ConfidenceLevel.MEDIUM); // 12 symptom days
    }

    @Test
    void sparseData_usesSeventyFifthPercentileFallback() {
        // Only 5 symptom days → below the min of 10 → fallback path.
        List<Double> symptom = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
        List<Double> nonSymptom = List.of(1.0, 2.0, 3.0, 4.0, 5.0);

        ThresholdResult r = finder.findThreshold(symptom, nonSymptom);

        assertThat(r.usedFallback()).isTrue();
        assertThat(r.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(r.symptomDaysUsed()).isEqualTo(5);
        // nearest-rank 75th percentile of [10,20,30,40,50] is 40
        assertThat(r.threshold()).isEqualTo(40.0);
        assertThat(r.youdenJ()).isNaN();
    }

    @Test
    void noSymptomDays_returnsNaNThreshold() {
        ThresholdResult r = finder.findThreshold(List.of(), List.of(1.0, 2.0, 3.0));
        assertThat(r.threshold()).isNaN();
        assertThat(r.symptomDaysUsed()).isZero();
        assertThat(r.confidence()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    void noNonSymptomDays_fallsBackEvenWithManyPositives() {
        // 12 symptom days but zero symptom-free days → can't compute specificity → fallback.
        List<Double> symptom = new ArrayList<>();
        for (int i = 0; i < 12; i++) symptom.add(10.0 + i);

        ThresholdResult r = finder.findThreshold(symptom, List.of());

        assertThat(r.usedFallback()).isTrue();
        assertThat(r.symptomDaysUsed()).isEqualTo(12);
    }
}
