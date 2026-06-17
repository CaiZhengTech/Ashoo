package com.ashoo.api.dto;

import com.ashoo.briefing.BriefingService.BriefingResult;

import java.time.Instant;

/**
 * API view of a daily briefing.
 *
 * {@code source} ("claude", "fallback", or "cached") is exposed for transparency and
 * debugging — the user/recruiter can see whether the live model or the offline template
 * produced the text. The {@code text} always ends with the mandatory disclaimer.
 */
public record BriefingResponse(
        String text,
        double riskScore,
        String riskLabel,
        String source,
        int tokensUsed,
        Instant generatedAt
) {
    /**
     * Maps a briefing result to its API shape.
     *
     * @param r the result from BriefingService
     * @return the response record
     */
    public static BriefingResponse from(BriefingResult r) {
        return new BriefingResponse(r.text(), r.riskScore(), r.riskLabel(),
                r.source(), r.tokensUsed(), r.generatedAt());
    }
}
