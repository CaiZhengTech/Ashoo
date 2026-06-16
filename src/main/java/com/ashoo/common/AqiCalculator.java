package com.ashoo.common;

/**
 * Computes the EPA Air Quality Index from raw PM2.5 concentrations.
 *
 * Uses the post-May 6, 2024 EPA breakpoints, which lowered the "Good" ceiling
 * from 12.0 to 9.0 µg/m³. Using old breakpoints would understate risk for
 * readings between 9.1 and 12.0 µg/m³. This class is stateless and final —
 * AQI computation is a pure function with no reason for inheritance.
 */
public final class AqiCalculator {

    private AqiCalculator() {}

    /** Each row: { PM2.5 low, PM2.5 high, AQI low, AQI high }. Verified 2024 breakpoints. */
    private static final double[][] BREAKPOINTS = {
        {   0.0,   9.0,   0,  50 },  // Good
        {   9.1,  35.4,  51, 100 },  // Moderate
        {  35.5,  55.4, 101, 150 },  // Unhealthy for Sensitive Groups
        {  55.5, 125.4, 151, 200 },  // Unhealthy
        { 125.5, 225.4, 201, 300 },  // Very Unhealthy
        { 225.5, 325.4, 301, 500 },  // Hazardous
    };

    /**
     * Computes the EPA AQI from a raw PM2.5 concentration.
     *
     * The formula is the standard EPA piecewise-linear interpolation:
     * {@code I = ((I_Hi - I_Lo) / (BP_Hi - BP_Lo)) * (C - BP_Lo) + I_Lo}
     * PM2.5 is truncated (not rounded) to one decimal place per the EPA spec.
     * This matters at boundaries: 9.09 truncates to 9.0 (Good), not 9.1 (Moderate).
     *
     * @param pm25 raw PM2.5 concentration in µg/m³
     * @return AQI integer (0–500), or -1 if input is null or negative
     */
    public static int computeFromPm25(Double pm25) {
        if (pm25 == null || pm25 < 0) {
            return -1;
        }

        double truncated = Math.floor(pm25 * 10.0) / 10.0;

        for (double[] bp : BREAKPOINTS) {
            if (truncated >= bp[0] && truncated <= bp[1]) {
                double aqi = ((bp[3] - bp[2]) / (bp[1] - bp[0])) * (truncated - bp[0]) + bp[2];
                return (int) Math.round(aqi);
            }
        }

        if (truncated > 325.4) {
            return 500;
        }
        return -1;
    }

    /**
     * Convenience wrapper that maps a Personal Risk Index to a risk level.
     *
     * @param pri Personal Risk Index value (0.0–100.0)
     * @return the corresponding RiskLevel enum value
     */
    public static RiskLevel toRiskLevel(double pri) {
        return RiskLevel.fromScore(pri);
    }
}
