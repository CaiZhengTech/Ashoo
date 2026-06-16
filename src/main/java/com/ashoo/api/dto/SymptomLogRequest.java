package com.ashoo.api.dto;

import java.time.Instant;

/**
 * Request body for creating or editing a symptom log entry.
 *
 * Severity is validated as 0–10 in the controller. loggedAt defaults
 * to now() if not provided. All other fields are optional.
 */
public record SymptomLogRequest(
        Instant loggedAt,
        Integer severity,
        String notes,
        Long locationId,
        String cityName,
        Long[] medicationsUsed
) {}
