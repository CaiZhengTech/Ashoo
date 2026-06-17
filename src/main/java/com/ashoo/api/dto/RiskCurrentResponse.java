package com.ashoo.api.dto;

import com.ashoo.correlation.RiskScoringService.FactorContribution;
import com.ashoo.correlation.RiskScoringService.RiskScoreBreakdown;

import java.time.Instant;
import java.util.List;

/**
 * API view of the current Personal Risk Index plus its factor breakdown.
 *
 * Bundles everything the dashboard's risk panel needs in one payload: the score,
 * its label and color, the alert state, the confidence context, and the ranked
 * factor contributions. Includes the mandatory Open-Meteo attribution because this
 * score is derived from their data.
 */
public record RiskCurrentResponse(
        double score,
        double rawScore,
        String label,
        String color,
        String guidance,
        boolean alertActive,
        String confidenceLevel,
        String confidenceMessage,
        int symptomDaysAvailable,
        List<FactorView> factors,
        Instant scoredAt,
        String attribution
) {
    private static final String ATTRIBUTION =
            "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)";

    /** One factor's contribution, flattened for JSON. */
    public record FactorView(String key, String displayName, double percentile,
                             boolean aboveThreshold, double weight,
                             Double value, String unit) {
        static FactorView from(FactorContribution c) {
            return new FactorView(c.key(), c.displayName(), c.percentile(),
                    c.aboveThreshold(), c.weight(), c.value(), c.unit());
        }
    }

    /**
     * Maps a scoring breakdown to its API shape.
     *
     * @param b the breakdown from RiskScoringService
     * @return the response record
     */
    public static RiskCurrentResponse from(RiskScoreBreakdown b) {
        List<FactorView> factors = b.factors().stream().map(FactorView::from).toList();
        return new RiskCurrentResponse(
                round(b.score().smoothedScore()),
                round(b.score().rawScore()),
                b.score().level().getLabel(),
                b.score().level().getColor(),
                b.score().level().getGuidance(),
                b.score().alertActive(),
                b.confidence().name(),
                b.confidence().getMessage(),
                b.symptomDaysAvailable(),
                factors,
                b.scoredAt(),
                ATTRIBUTION);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
