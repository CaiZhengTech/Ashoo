package com.ashoo.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every EPA 2024 PM2.5 breakpoint and edge case.
 *
 * These tests use the verified post-May 6, 2024 breakpoints. Each breakpoint
 * boundary is tested at its low end, high end, and a mid-range value to
 * confirm the linear interpolation formula is correct.
 */
class AqiCalculatorTest {

    @ParameterizedTest(name = "PM2.5 {0} µg/m³ → AQI {1}")
    @CsvSource({
        // Good: 0.0–9.0 → AQI 0–50
        "0.0,   0",
        "4.5,  25",
        "9.0,  50",
        // Moderate: 9.1–35.4 → AQI 51–100
        "9.1,  51",
        "22.2, 75",
        "35.4, 100",
        // USG: 35.5–55.4 → AQI 101–150
        "35.5, 101",
        "45.4, 125",
        "55.4, 150",
        // Unhealthy: 55.5–125.4 → AQI 151–200
        "55.5, 151",
        "90.4, 175",
        "125.4, 200",
        // Very Unhealthy: 125.5–225.4 → AQI 201–300
        "125.5, 201",
        "175.4, 250",
        "225.4, 300",
        // Hazardous: 225.5–325.4 → AQI 301–500
        "225.5, 301",
        "275.4, 400",
        "325.4, 500",
    })
    void breakpointValues(double pm25, int expectedAqi) {
        assertThat(AqiCalculator.computeFromPm25(pm25)).isEqualTo(expectedAqi);
    }

    @Test
    void aboveMaxBreakpointReturns500() {
        assertThat(AqiCalculator.computeFromPm25(500.0)).isEqualTo(500);
    }

    @Test
    void nullInputReturnsNegativeOne() {
        assertThat(AqiCalculator.computeFromPm25(null)).isEqualTo(-1);
    }

    @Test
    void negativeInputReturnsNegativeOne() {
        assertThat(AqiCalculator.computeFromPm25(-1.0)).isEqualTo(-1);
    }

    @Test
    void truncationNotRounding() {
        // 9.09 truncated to 9.0 → Good (AQI 50), NOT rounded to 9.1 → Moderate (AQI 51)
        assertThat(AqiCalculator.computeFromPm25(9.09)).isEqualTo(50);
    }

    @Test
    void zeroReturnsZero() {
        assertThat(AqiCalculator.computeFromPm25(0.0)).isEqualTo(0);
    }
}
