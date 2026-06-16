package com.ashoo.api.dto;

import com.ashoo.storage.entity.SavedLocation;

import java.time.Instant;

/** API response for a saved location. */
public record SavedLocationResponse(
        Long id,
        String label,
        String cityName,
        String county,
        String country,
        Double latitude,
        Double longitude,
        Boolean isPrimary,
        Boolean isActive,
        Instant createdAt
) {
    /**
     * Converts a SavedLocation entity to the API response shape.
     *
     * @param l the entity
     * @return the response record
     */
    public static SavedLocationResponse from(SavedLocation l) {
        return new SavedLocationResponse(
                l.getId(), l.getLabel(), l.getCityName(), l.getCounty(),
                l.getCountry(), l.getLatitude(), l.getLongitude(),
                l.getIsPrimary(), l.getIsActive(), l.getCreatedAt());
    }
}
