package com.ashoo.api.dto;

import com.ashoo.correlation.Factor;
import com.ashoo.storage.entity.CorrelationResult;

import java.time.Instant;

/**
 * API view of one factor's learned correlation with the user's symptoms.
 *
 * Adds a human-readable {@code displayName} (resolved from the factor key) so the
 * frontend doesn't have to keep its own key→label table, and surfaces the
 * confidence level prominently — Ashoo never shows a correlation number without
 * telling the user how much to trust it.
 */
public record CorrelationResultResponse(
        String factorName,
        String displayName,
        Integer bestLagHours,
        Double spearmanR,
        Double pointBiserialR,
        Double personalThreshold,
        Double thresholdPercentile,
        Double weight,
        String confidenceLevel,
        Integer symptomDaysUsed,
        Integer totalDaysUsed,
        Integer mismatchCount,
        Instant computedAt
) {
    /**
     * Maps a stored correlation row to its API shape.
     *
     * @param r the entity
     * @return the response record
     */
    public static CorrelationResultResponse from(CorrelationResult r) {
        Factor factor = Factor.fromKey(r.getFactorName());
        String displayName = factor != null ? factor.getDisplayName() : r.getFactorName();
        return new CorrelationResultResponse(
                r.getFactorName(), displayName, r.getBestLagHours(),
                r.getSpearmanR(), r.getPointBiserialR(),
                r.getPersonalThreshold(), r.getThresholdPercentile(), r.getWeight(),
                r.getConfidenceLevel(), r.getSymptomDaysUsed(), r.getTotalDaysUsed(),
                r.getMismatchCount(), r.getComputedAt());
    }
}
