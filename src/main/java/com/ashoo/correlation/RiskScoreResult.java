package com.ashoo.correlation;

import com.ashoo.common.RiskLevel;

/**
 * The output of one risk-scoring run.
 *
 * Carries both the raw weighted score and the EWMA-smoothed score because they
 * answer different questions: the raw score is "how do conditions look right now,"
 * while the smoothed score is what drives the user-facing label and alert state
 * (it resists hour-to-hour flapping). Keeping both lets the dashboard show the
 * stable number while still charting the raw signal.
 *
 * @param rawScore       the weighted-sum Personal Risk Index for this moment (0–100)
 * @param smoothedScore  the EWMA-smoothed score (0–100) — what the UI displays
 * @param alertActive    whether an alert is active after applying hysteresis
 * @param level          the RiskLevel bucket for the smoothed score (Great…Severe)
 */
public record RiskScoreResult(
        double rawScore,
        double smoothedScore,
        boolean alertActive,
        RiskLevel level
) {}
