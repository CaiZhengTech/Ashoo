package com.ashoo.ingestion;

import com.ashoo.storage.entity.EnvironmentalSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for derived signal computation.
 *
 * Tests the rate-of-change, pressure-drop, AQI, and thunderstorm-flag
 * calculations in isolation — no database, no Spring context needed.
 */
class DerivedSignalCalculatorTest {

    private final DerivedSignalCalculator calc = new DerivedSignalCalculator();

    @Test
    void rateOfChangeComputesDelta() {
        assertThat(calc.computeRateOfChange(15.0, 10.0)).isEqualTo(5.0);
    }

    @Test
    void rateOfChangeReturnsNullWhenMissing() {
        assertThat(calc.computeRateOfChange(null, 10.0)).isNull();
        assertThat(calc.computeRateOfChange(15.0, null)).isNull();
    }

    @Test
    void pressureDropComputesDelta() {
        assertThat(calc.computePressureDrop(1010.0, 1015.0)).isEqualTo(-5.0);
    }

    @Test
    void thunderstormFlagTrueWhenAllConditionsMet() {
        // High grass pollen (>30), strong gusts (>10 m/s), pressure dropping (< -2 hPa)
        assertThat(calc.computeThunderstormFlag(35.0, 15.0, -3.0)).isTrue();
    }

    @Test
    void thunderstormFlagFalseWhenPollenLow() {
        assertThat(calc.computeThunderstormFlag(10.0, 15.0, -3.0)).isFalse();
    }

    @Test
    void thunderstormFlagFalseWhenWindCalm() {
        assertThat(calc.computeThunderstormFlag(35.0, 5.0, -3.0)).isFalse();
    }

    @Test
    void thunderstormFlagFalseWhenPressureStable() {
        assertThat(calc.computeThunderstormFlag(35.0, 15.0, -1.0)).isFalse();
    }

    @Test
    void thunderstormFlagFalseWhenNullInputs() {
        assertThat(calc.computeThunderstormFlag(null, 15.0, -3.0)).isFalse();
    }

    @Test
    void computeSetsAllDerivedFieldsWithPrevious() {
        EnvironmentalSnapshot current = EnvironmentalSnapshot.builder()
                .recordedAt(Instant.now())
                .pm25(20.0)
                .pressureMslHpa(1010.0)
                .pollenGrass(5.0)
                .windGustsMs(3.0)
                .dataSource("OPEN_METEO")
                .build();

        EnvironmentalSnapshot previous = EnvironmentalSnapshot.builder()
                .recordedAt(Instant.now().minusSeconds(3600))
                .pm25(15.0)
                .pressureMslHpa(1013.0)
                .dataSource("OPEN_METEO")
                .build();

        calc.compute(current, Optional.of(previous));

        assertThat(current.getPm25RateOfChange()).isEqualTo(5.0);
        assertThat(current.getPressureDrop3h()).isEqualTo(-3.0);
        assertThat(current.getAqiComputed()).isNotNull();
        assertThat(current.getThunderstormFlag()).isFalse();
    }

    @Test
    void computeHandlesMissingPrevious() {
        EnvironmentalSnapshot current = EnvironmentalSnapshot.builder()
                .recordedAt(Instant.now())
                .pm25(20.0)
                .dataSource("OPEN_METEO")
                .build();

        calc.compute(current, Optional.empty());

        assertThat(current.getPm25RateOfChange()).isNull();
        assertThat(current.getPressureDrop3h()).isNull();
        assertThat(current.getAqiComputed()).isNotNull();
    }
}
