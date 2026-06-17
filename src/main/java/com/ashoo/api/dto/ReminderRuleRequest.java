package com.ashoo.api.dto;

import java.time.LocalTime;

/**
 * Request body for creating a reminder rule.
 *
 * The {@code userNote} is written entirely by the user and echoed back when the rule
 * fires. The time window is optional — both null means "always active." Times bind from
 * ISO strings like "09:00".
 *
 * @param riskScoreThreshold score (0–100) at/above which the rule fires
 * @param userNote           the user's own reminder note
 * @param medicationId       optional linked medication id
 * @param timeWindowStart    optional window start (e.g. "09:00")
 * @param timeWindowEnd      optional window end (e.g. "21:00")
 */
public record ReminderRuleRequest(
        Double riskScoreThreshold,
        String userNote,
        Long medicationId,
        LocalTime timeWindowStart,
        LocalTime timeWindowEnd
) {}
