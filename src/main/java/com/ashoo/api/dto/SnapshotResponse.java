package com.ashoo.api.dto;

import com.ashoo.storage.entity.EnvironmentalSnapshot;

import java.time.Instant;

/**
 * API response DTO for environmental snapshot data.
 *
 * Records are ideal for DTOs — immutable, compact, auto-generated equals/hashCode,
 * and Jackson serializes them without any annotations. This record maps from the
 * internal entity to the public API shape, which lets us evolve the DB schema
 * without breaking API consumers.
 */
public record SnapshotResponse(
        Instant recordedAt,
        String userId,
        String cityName,
        Double latitude,
        Double longitude,
        // Air quality
        Double pm25,
        Double pm10,
        Double o3,
        Double no2,
        Double so2,
        Double co,
        // Pollen
        Double pollenAlder,
        Double pollenBirch,
        Double pollenGrass,
        Double pollenMugwort,
        Double pollenOlive,
        Double pollenRagweed,
        // Weather
        Double temperatureC,
        Double humidityPct,
        Double pressureMslHpa,
        Double windSpeedMs,
        Double windGustsMs,
        // Derived
        Double pm25RateOfChange,
        Double pressureDrop3h,
        Double cumulativePm2524h,
        Integer aqiComputed,
        Boolean thunderstormFlag,
        // Provenance
        String dataSource,
        String dataOrigin,
        // Required attribution
        String attribution
) {

    private static final String ATTRIBUTION =
            "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)";

    /**
     * Converts an internal entity to the public API response.
     *
     * @param s the entity from the database
     * @return the API response with attribution included
     */
    public static SnapshotResponse from(EnvironmentalSnapshot s) {
        return new SnapshotResponse(
                s.getRecordedAt(), s.getUserId(), s.getCityName(),
                s.getLatitude(), s.getLongitude(),
                s.getPm25(), s.getPm10(), s.getO3(), s.getNo2(), s.getSo2(), s.getCo(),
                s.getPollenAlder(), s.getPollenBirch(), s.getPollenGrass(),
                s.getPollenMugwort(), s.getPollenOlive(), s.getPollenRagweed(),
                s.getTemperatureC(), s.getHumidityPct(), s.getPressureMslHpa(),
                s.getWindSpeedMs(), s.getWindGustsMs(),
                s.getPm25RateOfChange(), s.getPressureDrop3h(), s.getCumulativePm2524h(),
                s.getAqiComputed(), s.getThunderstormFlag(),
                s.getDataSource(), s.getDataOrigin(),
                ATTRIBUTION
        );
    }
}
