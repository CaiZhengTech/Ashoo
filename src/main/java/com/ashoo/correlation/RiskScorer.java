package com.ashoo.correlation;

import com.ashoo.common.AshooProperties;
import com.ashoo.common.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Aggregates normalized factor scores into a single Personal Risk Index, then
 * smooths it over time and decides the alert state.
 *
 * Three steps, each with a clear purpose:
 * <ol>
 *   <li><b>Weighted sum</b> — each factor contributes in proportion to how strongly
 *       it correlates with the user's symptoms, so the score reflects <em>their</em>
 *       triggers, not a population average.</li>
 *   <li><b>EWMA smoothing (λ=0.3)</b> — blends 30% of the new reading with 70% of the
 *       running average, damping hour-to-hour noise without going numb to real events.</li>
 *   <li><b>Hysteresis</b> — an alert turns on at 70 but only clears below 55, so the
 *       state cannot "flap" on and off while the score hovers at a boundary.</li>
 * </ol>
 *
 * Tuning constants come from {@code ashoo.correlation.*} config, so behavior can be
 * adjusted without recompiling.
 */
@Component
public class RiskScorer {

    private final double lambda;
    private final double alertOnThreshold;
    private final double alertOffThreshold;

    public RiskScorer(AshooProperties props) {
        AshooProperties.CorrelationConfig c = props.correlation();
        this.lambda = c.ewmaLambda();
        this.alertOnThreshold = c.alertOnThreshold();
        this.alertOffThreshold = c.alertOffThreshold();
    }

    /**
     * Computes the risk score for one moment in time.
     *
     * The weighted sum is renormalized over only the factors actually present in
     * {@code normalizedScores}: if a factor is missing for this reading (e.g. pollen
     * is null), it is dropped and the remaining weights are rescaled to sum to 1,
     * rather than letting the gap silently drag the score down.
     *
     * @param normalizedScores    factor key → percentile score (0–100) for current conditions
     * @param factorWeights       factor key → importance weight (the full set, summing to ~1.0)
     * @param previousSmoothed    the prior EWMA score, or {@code null} on the first ever run
     *                            (in which case the smoothed score is seeded to the raw score)
     * @param previousAlertActive whether an alert was active at the previous reading
     * @return the raw and smoothed scores, the resulting alert state, and the RiskLevel
     */
    public RiskScoreResult computeScore(Map<String, Double> normalizedScores,
                                        Map<String, Double> factorWeights,
                                        Double previousSmoothed,
                                        boolean previousAlertActive) {
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (Map.Entry<String, Double> entry : normalizedScores.entrySet()) {
            Double w = factorWeights.get(entry.getKey());
            if (w == null || w == 0.0) continue;
            weightedSum += w * entry.getValue();
            weightTotal += w;
        }

        double raw = weightTotal > 0 ? weightedSum / weightTotal : 0.0;
        return smoothAndClassify(raw, previousSmoothed, previousAlertActive);
    }

    /**
     * Applies EWMA smoothing and hysteresis to an already-computed raw score.
     *
     * Exposed separately so callers that build the raw score from more than a plain
     * weighted average (for example, blending trigger intensity with how many of the
     * person's triggers are currently active) still get identical smoothing and
     * alert-state behavior, keeping that logic in one place.
     *
     * @param raw                 the 0-100 raw score for this moment
     * @param previousSmoothed    the prior EWMA score, or null on the first ever run
     * @param previousAlertActive whether an alert was active at the previous reading
     * @return the raw and smoothed scores, alert state, and RiskLevel
     */
    public RiskScoreResult smoothAndClassify(double raw, Double previousSmoothed,
                                             boolean previousAlertActive) {
        raw = clamp(raw);

        double smoothed = previousSmoothed == null
                ? raw
                : lambda * raw + (1 - lambda) * previousSmoothed;
        smoothed = clamp(smoothed);

        boolean alertActive = previousAlertActive
                ? smoothed >= alertOffThreshold   // stay on until it drops below the off-threshold
                : smoothed >= alertOnThreshold;   // turn on only once it crosses the on-threshold

        return new RiskScoreResult(raw, smoothed, alertActive, RiskLevel.fromScore(smoothed));
    }

    /**
     * Weighted mean of factor inputs, renormalized over only the factors present.
     *
     * @param inputs  factor key → 0-100 value
     * @param weights factor key → weight
     * @return the weighted average, or 0 if no weighted factors are present
     */
    public static double weightedMean(Map<String, Double> inputs, Map<String, Double> weights) {
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (Map.Entry<String, Double> e : inputs.entrySet()) {
            Double w = weights.get(e.getKey());
            if (w == null || w == 0.0) continue;
            weightedSum += w * e.getValue();
            weightTotal += w;
        }
        return weightTotal > 0 ? weightedSum / weightTotal : 0.0;
    }

    /**
     * Clamps a score into the valid Personal Risk Index range.
     *
     * @param v a score that should be 0–100 but may drift slightly out of range
     * @return v constrained to [0, 100]
     */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }
}
