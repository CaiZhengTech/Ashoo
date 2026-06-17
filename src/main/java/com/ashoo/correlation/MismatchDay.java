package com.ashoo.correlation;

import java.time.LocalDate;

/**
 * A day where the model's reconstructed risk score disagreed with what the user
 * actually logged — surfaced deliberately for transparency.
 *
 * Mismatch transparency is a core Ashoo differentiator: instead of hiding the
 * model's failures, we show them. "On Oct 3 your score was 85 but you felt fine"
 * tells the user the engine has blind spots (indoor triggers, stress, exercise)
 * and invites them to correct a mis-logged day. Honesty here builds the trust a
 * health tool needs.
 *
 * @param date            the calendar day in question
 * @param reconstructedScore  the 0–100 score the current model assigns to that day's conditions
 * @param loggedSeverity  the worst symptom severity the user logged that day (0 = none)
 * @param type            which kind of disagreement this is
 */
public record MismatchDay(
        LocalDate date,
        double reconstructedScore,
        int loggedSeverity,
        Type type
) {
    /**
     * The two directions a prediction can be wrong. Both are informative:
     * a false alarm may mean we over-weight a factor; a missed flare may mean
     * we are not measuring the real trigger at all.
     */
    public enum Type {
        /** High predicted score, but the user reported no symptoms. */
        HIGH_SCORE_NO_SYMPTOMS,
        /** Low predicted score, but the user reported a significant episode. */
        LOW_SCORE_SEVERE_SYMPTOMS
    }

    /**
     * The size of the disagreement, used to rank mismatches most-surprising-first.
     *
     * For a false alarm this is the score itself (how loud the alarm was); for a
     * missed flare it is the severity scaled to the same 0–100 range, so the two
     * types sort sensibly against each other.
     *
     * @return a non-negative magnitude of the discrepancy
     */
    public double discrepancy() {
        return switch (type) {
            case HIGH_SCORE_NO_SYMPTOMS -> reconstructedScore;
            case LOW_SCORE_SEVERE_SYMPTOMS -> loggedSeverity * 10.0;
        };
    }
}
