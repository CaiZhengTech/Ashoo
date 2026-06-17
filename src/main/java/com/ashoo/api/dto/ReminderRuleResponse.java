package com.ashoo.api.dto;

import com.ashoo.storage.entity.ReminderRule;

import java.time.LocalTime;

/**
 * API view of a saved reminder rule.
 */
public record ReminderRuleResponse(
        Long id,
        Double riskScoreThreshold,
        String userNote,
        Long medicationId,
        LocalTime timeWindowStart,
        LocalTime timeWindowEnd
) {
    /**
     * Maps a reminder-rule entity to its API shape.
     *
     * @param r the entity
     * @return the response record
     */
    public static ReminderRuleResponse from(ReminderRule r) {
        return new ReminderRuleResponse(r.getId(), r.getRiskScoreThreshold(), r.getUserNote(),
                r.getMedicationId(), r.getTimeWindowStart(), r.getTimeWindowEnd());
    }
}
