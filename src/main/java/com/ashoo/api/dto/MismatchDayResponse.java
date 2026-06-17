package com.ashoo.api.dto;

import com.ashoo.correlation.MismatchDay;

import java.time.LocalDate;

/**
 * API view of a single model/reality mismatch day.
 *
 * Includes a plain-English explanation so the frontend can render the transparency
 * view directly: "Score was 85 but you logged no symptoms." Surfacing these honestly
 * is a deliberate trust-building feature, not an error report.
 */
public record MismatchDayResponse(
        LocalDate date,
        double reconstructedScore,
        int loggedSeverity,
        String type,
        String explanation
) {
    /**
     * Maps a MismatchDay to its API shape with a human-readable explanation.
     *
     * @param m the mismatch
     * @return the response record
     */
    public static MismatchDayResponse from(MismatchDay m) {
        String explanation = switch (m.type()) {
            case HIGH_SCORE_NO_SYMPTOMS -> String.format(
                    "Score was %.0f but you logged no symptoms.", m.reconstructedScore());
            case LOW_SCORE_SEVERE_SYMPTOMS -> String.format(
                    "Score was only %.0f but you logged a severity-%d day.",
                    m.reconstructedScore(), m.loggedSeverity());
        };
        return new MismatchDayResponse(
                m.date(), Math.round(m.reconstructedScore() * 10.0) / 10.0,
                m.loggedSeverity(), m.type().name(), explanation);
    }
}
