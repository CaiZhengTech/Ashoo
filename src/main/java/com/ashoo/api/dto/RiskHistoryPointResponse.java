package com.ashoo.api.dto;

import com.ashoo.storage.entity.RiskScoreHistory;

import java.time.Instant;

/**
 * API view of one historical risk-score reading, shaped for a trend chart.
 *
 * Returns the smoothed score (what the user saw) alongside the raw score, so the
 * chart can plot the stable line and optionally overlay the noisier signal.
 */
public record RiskHistoryPointResponse(
        Instant scoredAt,
        Double score,
        Double rawScore,
        String label,
        Boolean alertTriggered
) {
    /**
     * Maps a stored risk-score row to its API shape.
     *
     * @param r the entity
     * @return the response record
     */
    public static RiskHistoryPointResponse from(RiskScoreHistory r) {
        return new RiskHistoryPointResponse(
                r.getScoredAt(), r.getRiskScoreSmoothed(), r.getRiskScore(),
                r.getRiskLabel(), r.getAlertTriggered());
    }
}
