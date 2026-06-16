package com.ashoo.storage.entity;

import java.time.Instant;

/**
 * One hourly environmental reading — the fundamental data unit in Ashoo.
 *
 * This class maps 1:1 to the {@code environmental_snapshot} hypertable.
 * It is a plain Java class (not a Spring Data entity) because the hypertable
 * has no primary key column — TimescaleDB partitions by {@code recorded_at}
 * instead. Without an {@code @Id} field, Spring Data JDBC's CrudRepository
 * cannot manage this class, so we use JdbcTemplate directly via
 * {@link com.ashoo.storage.repository.EnvironmentalSnapshotRepository}.
 *
 * Use {@link #builder()} to construct instances — the builder pattern is
 * cleaner than a 28-argument constructor for a class with this many fields.
 */
public class EnvironmentalSnapshot {

    // --- Time and identity ---
    private Instant recordedAt;
    private String userId = "ashoo-user";
    private Long locationId;
    private Double latitude;
    private Double longitude;
    private String cityName;

    // --- Air quality (µg/m³) ---
    private Double pm25;
    private Double pm10;
    private Double o3;
    private Double no2;
    private Double so2;
    private Double co;

    // --- Pollen (grains/m³, Europe only via Open-Meteo CAMS) ---
    private Double pollenAlder;
    private Double pollenBirch;
    private Double pollenGrass;
    private Double pollenMugwort;
    private Double pollenOlive;
    private Double pollenRagweed;

    // --- Meteorological ---
    private Double temperatureC;
    private Double humidityPct;
    private Double pressureMslHpa;
    private Double windSpeedMs;
    private Double windGustsMs;

    // --- Derived signals (computed at ingest time) ---
    private Double pm25RateOfChange;
    private Double pressureDrop3h;
    private Double cumulativePm2524h;
    private Integer aqiComputed;
    private Boolean thunderstormFlag;

    // --- Provenance ---
    private String dataSource;
    private String dataOrigin = "REAL";

    public EnvironmentalSnapshot() {}

    // --- Getters ---

    public Instant getRecordedAt()       { return recordedAt; }
    public String  getUserId()           { return userId; }
    public Long    getLocationId()       { return locationId; }
    public Double  getLatitude()         { return latitude; }
    public Double  getLongitude()        { return longitude; }
    public String  getCityName()         { return cityName; }

    public Double  getPm25()             { return pm25; }
    public Double  getPm10()             { return pm10; }
    public Double  getO3()               { return o3; }
    public Double  getNo2()              { return no2; }
    public Double  getSo2()              { return so2; }
    public Double  getCo()               { return co; }

    public Double  getPollenAlder()      { return pollenAlder; }
    public Double  getPollenBirch()      { return pollenBirch; }
    public Double  getPollenGrass()      { return pollenGrass; }
    public Double  getPollenMugwort()    { return pollenMugwort; }
    public Double  getPollenOlive()      { return pollenOlive; }
    public Double  getPollenRagweed()    { return pollenRagweed; }

    public Double  getTemperatureC()     { return temperatureC; }
    public Double  getHumidityPct()      { return humidityPct; }
    public Double  getPressureMslHpa()   { return pressureMslHpa; }
    public Double  getWindSpeedMs()      { return windSpeedMs; }
    public Double  getWindGustsMs()      { return windGustsMs; }

    public Double  getPm25RateOfChange() { return pm25RateOfChange; }
    public Double  getPressureDrop3h()   { return pressureDrop3h; }
    public Double  getCumulativePm2524h(){ return cumulativePm2524h; }
    public Integer getAqiComputed()      { return aqiComputed; }
    public Boolean getThunderstormFlag() { return thunderstormFlag; }

    public String  getDataSource()       { return dataSource; }
    public String  getDataOrigin()       { return dataOrigin; }

    // --- Setters for derived signals (called after initial construction) ---

    /**
     * Sets all derived signals at once. Called by DerivedSignalCalculator
     * after the raw reading has been constructed from the API response.
     *
     * @param rateOfChange hourly PM2.5 delta (spike detection)
     * @param pressureDrop 3-hour pressure change (storm proxy)
     * @param cumulative24h rolling 24h PM2.5 burden
     * @param aqi computed EPA AQI from PM2.5
     * @param thunderstorm heuristic thunderstorm-asthma flag
     */
    public void setDerivedSignals(Double rateOfChange, Double pressureDrop,
                                   Double cumulative24h, Integer aqi,
                                   Boolean thunderstorm) {
        this.pm25RateOfChange = rateOfChange;
        this.pressureDrop3h = pressureDrop;
        this.cumulativePm2524h = cumulative24h;
        this.aqiComputed = aqi;
        this.thunderstormFlag = thunderstorm;
    }

    /**
     * Creates a new builder for constructing an EnvironmentalSnapshot.
     *
     * @return a fresh Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for EnvironmentalSnapshot.
     *
     * Avoids a 28-argument constructor by letting each field be set independently.
     * Fields not explicitly set keep their defaults (null for objects, "ashoo-user"
     * for userId, "REAL" for dataOrigin).
     */
    public static class Builder {
        private final EnvironmentalSnapshot s = new EnvironmentalSnapshot();

        public Builder recordedAt(Instant v)       { s.recordedAt = v; return this; }
        public Builder userId(String v)            { s.userId = v; return this; }
        public Builder locationId(Long v)          { s.locationId = v; return this; }
        public Builder latitude(Double v)          { s.latitude = v; return this; }
        public Builder longitude(Double v)         { s.longitude = v; return this; }
        public Builder cityName(String v)          { s.cityName = v; return this; }

        public Builder pm25(Double v)              { s.pm25 = v; return this; }
        public Builder pm10(Double v)              { s.pm10 = v; return this; }
        public Builder o3(Double v)                { s.o3 = v; return this; }
        public Builder no2(Double v)               { s.no2 = v; return this; }
        public Builder so2(Double v)               { s.so2 = v; return this; }
        public Builder co(Double v)                { s.co = v; return this; }

        public Builder pollenAlder(Double v)       { s.pollenAlder = v; return this; }
        public Builder pollenBirch(Double v)       { s.pollenBirch = v; return this; }
        public Builder pollenGrass(Double v)       { s.pollenGrass = v; return this; }
        public Builder pollenMugwort(Double v)     { s.pollenMugwort = v; return this; }
        public Builder pollenOlive(Double v)       { s.pollenOlive = v; return this; }
        public Builder pollenRagweed(Double v)     { s.pollenRagweed = v; return this; }

        public Builder temperatureC(Double v)      { s.temperatureC = v; return this; }
        public Builder humidityPct(Double v)       { s.humidityPct = v; return this; }
        public Builder pressureMslHpa(Double v)    { s.pressureMslHpa = v; return this; }
        public Builder windSpeedMs(Double v)       { s.windSpeedMs = v; return this; }
        public Builder windGustsMs(Double v)       { s.windGustsMs = v; return this; }

        public Builder pm25RateOfChange(Double v)  { s.pm25RateOfChange = v; return this; }
        public Builder pressureDrop3h(Double v)    { s.pressureDrop3h = v; return this; }
        public Builder cumulativePm2524h(Double v) { s.cumulativePm2524h = v; return this; }
        public Builder aqiComputed(Integer v)      { s.aqiComputed = v; return this; }
        public Builder thunderstormFlag(Boolean v) { s.thunderstormFlag = v; return this; }

        public Builder dataSource(String v)        { s.dataSource = v; return this; }
        public Builder dataOrigin(String v)        { s.dataOrigin = v; return this; }

        /** @return the constructed EnvironmentalSnapshot */
        public EnvironmentalSnapshot build() { return s; }
    }
}
