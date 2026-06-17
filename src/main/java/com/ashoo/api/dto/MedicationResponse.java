package com.ashoo.api.dto;

import com.ashoo.storage.entity.Medication;

import java.time.Instant;

/**
 * API view of a registered medication.
 */
public record MedicationResponse(
        Long id,
        String name,
        String type,
        String notes,
        Instant createdAt
) {
    /**
     * Maps a medication entity to its API shape.
     *
     * @param m the entity
     * @return the response record
     */
    public static MedicationResponse from(Medication m) {
        return new MedicationResponse(m.getId(), m.getName(), m.getMedType(),
                m.getNotes(), m.getCreatedAt());
    }
}
