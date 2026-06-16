package com.ashoo.api.dto;

import com.ashoo.storage.entity.SymptomLog;

import java.time.Instant;

/** API response for a symptom log entry. */
public record SymptomLogResponse(
        Long id,
        Instant loggedAt,
        Instant createdAt,
        Instant updatedAt,
        String userId,
        Integer severity,
        String notes,
        Long locationId,
        String cityName,
        Long[] medicationsUsed,
        String dataOrigin
) {
    /**
     * Converts a SymptomLog entity to the API response shape.
     *
     * @param s the entity
     * @return the response record
     */
    public static SymptomLogResponse from(SymptomLog s) {
        return new SymptomLogResponse(
                s.getId(), s.getLoggedAt(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getUserId(), s.getSeverity(), s.getNotes(),
                s.getLocationId(), s.getCityName(), s.getMedicationsUsed(), s.getDataOrigin());
    }
}
