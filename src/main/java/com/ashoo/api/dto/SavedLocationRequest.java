package com.ashoo.api.dto;

/**
 * Request body for creating or updating a saved location.
 *
 * All fields except label, cityName, latitude, and longitude are optional —
 * country defaults to "US" and isPrimary defaults to false in the service layer.
 */
public record SavedLocationRequest(
        String label,
        String cityName,
        String county,
        String country,
        Double latitude,
        Double longitude,
        Boolean isPrimary
) {}
