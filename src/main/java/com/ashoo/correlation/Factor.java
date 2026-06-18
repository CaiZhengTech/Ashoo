package com.ashoo.correlation;

import com.ashoo.storage.entity.EnvironmentalSnapshot;

import java.util.function.Function;

/**
 * The catalog of environmental factors the correlation engine evaluates.
 *
 * Each factor pairs a stable string name (used as the {@code factor_name} key in
 * the {@code correlation_result} table and in API responses) with a function that
 * pulls that factor's value out of an {@link EnvironmentalSnapshot}. Centralizing
 * the list here means adding a new factor to the whole engine — correlation,
 * scoring, briefing — is a one-line change in this enum rather than edits scattered
 * across many classes.
 *
 * The extractor returns a boxed {@link Double} so a missing reading is {@code null}
 * rather than a misleading 0.0. Factors that are entirely null for a user (e.g.
 * pollen in the US, which Open-Meteo does not cover) simply produce no aligned
 * data pairs and fall out of the model with zero weight — no special-casing needed.
 */
public enum Factor {

    PM25("pm25", "PM2.5", "µg/m³", EnvironmentalSnapshot::getPm25),
    PM10("pm10", "PM10", "µg/m³", EnvironmentalSnapshot::getPm10),
    O3("o3", "Ozone", "µg/m³", EnvironmentalSnapshot::getO3),
    NO2("no2", "Nitrogen dioxide", "µg/m³", EnvironmentalSnapshot::getNo2),
    SO2("so2", "Sulfur dioxide", "µg/m³", EnvironmentalSnapshot::getSo2),
    CO("co", "Carbon monoxide", "µg/m³", EnvironmentalSnapshot::getCo),

    POLLEN_ALDER("pollen_alder", "Alder pollen", "grains/m³", EnvironmentalSnapshot::getPollenAlder),
    POLLEN_BIRCH("pollen_birch", "Birch pollen", "grains/m³", EnvironmentalSnapshot::getPollenBirch),
    POLLEN_GRASS("pollen_grass", "Grass pollen", "grains/m³", EnvironmentalSnapshot::getPollenGrass),
    POLLEN_MUGWORT("pollen_mugwort", "Mugwort pollen", "grains/m³", EnvironmentalSnapshot::getPollenMugwort),
    POLLEN_OLIVE("pollen_olive", "Olive pollen", "grains/m³", EnvironmentalSnapshot::getPollenOlive),
    POLLEN_RAGWEED("pollen_ragweed", "Ragweed pollen", "grains/m³", EnvironmentalSnapshot::getPollenRagweed),

    TEMPERATURE("temperature_c", "Temperature", "°C", EnvironmentalSnapshot::getTemperatureC),
    HUMIDITY("humidity_pct", "Humidity", "%", EnvironmentalSnapshot::getHumidityPct),
    PRESSURE("pressure_msl_hpa", "Barometric pressure", "hPa", EnvironmentalSnapshot::getPressureMslHpa),
    WIND_SPEED("wind_speed_ms", "Wind speed", "m/s", EnvironmentalSnapshot::getWindSpeedMs),
    WIND_GUSTS("wind_gusts_ms", "Wind gusts", "m/s", EnvironmentalSnapshot::getWindGustsMs),

    CUMULATIVE_PM25_24H("cumulative_pm25_24h", "24h cumulative PM2.5", "µg/m³",
            EnvironmentalSnapshot::getCumulativePm2524h),
    PM25_RATE_OF_CHANGE("pm25_rate_of_change", "PM2.5 rate of change", "µg/m³·h",
            EnvironmentalSnapshot::getPm25RateOfChange),
    PRESSURE_DROP_3H("pressure_drop_3h", "3h pressure drop", "hPa",
            EnvironmentalSnapshot::getPressureDrop3h);

    private final String key;
    private final String displayName;
    private final String unit;
    private final Function<EnvironmentalSnapshot, Double> extractor;

    Factor(String key, String displayName, String unit,
           Function<EnvironmentalSnapshot, Double> extractor) {
        this.key = key;
        this.displayName = displayName;
        this.unit = unit;
        this.extractor = extractor;
    }

    /**
     * Pulls this factor's value from a snapshot.
     *
     * @param snapshot the environmental reading to read from
     * @return the factor value, or {@code null} if this snapshot did not record it
     */
    public Double extract(EnvironmentalSnapshot snapshot) {
        return extractor.apply(snapshot);
    }

    /**
     * @return the stable snake_case key stored in the database and used in API responses
     */
    public String getKey() {
        return key;
    }

    /**
     * @return a human-readable label for UI display (e.g. "Grass pollen")
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return the physical unit this factor is measured in (e.g. "µg/m³", "°C"),
     *         so the UI can show a real reading rather than a bare number
     */
    public String getUnit() {
        return unit;
    }

    /**
     * Looks up a factor by its stable key.
     *
     * @param key the snake_case key (e.g. "pollen_grass")
     * @return the matching Factor, or {@code null} if no factor uses that key
     */
    public static Factor fromKey(String key) {
        for (Factor f : values()) {
            if (f.key.equals(key)) return f;
        }
        return null;
    }
}
