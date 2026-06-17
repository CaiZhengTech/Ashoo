package com.ashoo.correlation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds days where the model's score disagreed with the user's logged symptoms.
 *
 * This is the transparency engine behind Ashoo's "mismatch days" view. It is a pure
 * function over two day-keyed maps, so it does not care whether the scores came from
 * live history or from re-running the current model over past conditions — the caller
 * decides. Keeping it stateless makes the disagreement logic easy to unit-test in
 * isolation from any database.
 */
@Component
public class MismatchDetector {

    /** Default score at/above which we call a no-symptom day a false alarm. */
    public static final double DEFAULT_HIGH_SCORE = 70.0;
    /** Default score at/below which a severe day counts as a missed flare. */
    public static final double DEFAULT_LOW_SCORE = 40.0;
    /** Default severity at/above which a low-scored day counts as a missed flare. */
    public static final int DEFAULT_SEVERE_SYMPTOM = 6;

    /**
     * Detects mismatches using the default thresholds.
     *
     * @param scoreByDay    reconstructed risk score per day (0–100)
     * @param severityByDay logged max severity per day (absent day = no symptoms = 0)
     * @return mismatch days, most-surprising first
     */
    public List<MismatchDay> detect(Map<LocalDate, Double> scoreByDay,
                                    Map<LocalDate, Integer> severityByDay) {
        return detect(scoreByDay, severityByDay,
                DEFAULT_HIGH_SCORE, DEFAULT_LOW_SCORE, DEFAULT_SEVERE_SYMPTOM);
    }

    /**
     * Detects the two kinds of model/reality disagreement.
     *
     * A false alarm (high score, no symptoms) hints the model over-weights something;
     * a missed flare (low score, severe symptoms) hints at a trigger we are not yet
     * measuring. We surface both rather than only the flattering ones.
     *
     * @param scoreByDay           reconstructed risk score per day (0–100)
     * @param severityByDay        logged max severity per day (absent day treated as 0)
     * @param highScoreThreshold   score ≥ this with no symptoms = false alarm
     * @param lowScoreThreshold    score ≤ this with a severe day = missed flare
     * @param severeSymptomThreshold severity ≥ this counts as "severe" for missed flares
     * @return mismatch days ordered by discrepancy magnitude, descending
     */
    public List<MismatchDay> detect(Map<LocalDate, Double> scoreByDay,
                                    Map<LocalDate, Integer> severityByDay,
                                    double highScoreThreshold,
                                    double lowScoreThreshold,
                                    int severeSymptomThreshold) {
        List<MismatchDay> mismatches = new ArrayList<>();

        for (Map.Entry<LocalDate, Double> entry : scoreByDay.entrySet()) {
            LocalDate day = entry.getKey();
            double score = entry.getValue();
            int severity = severityByDay.getOrDefault(day, 0);

            if (score >= highScoreThreshold && severity == 0) {
                mismatches.add(new MismatchDay(day, score, 0,
                        MismatchDay.Type.HIGH_SCORE_NO_SYMPTOMS));
            } else if (score <= lowScoreThreshold && severity >= severeSymptomThreshold) {
                mismatches.add(new MismatchDay(day, score, severity,
                        MismatchDay.Type.LOW_SCORE_SEVERE_SYMPTOMS));
            }
        }

        mismatches.sort(Comparator.comparingDouble(MismatchDay::discrepancy).reversed());
        return mismatches;
    }
}
