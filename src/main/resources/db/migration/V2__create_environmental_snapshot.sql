-- The core time-series table. One row per hourly API poll per location.
-- Every column the correlation engine reads lives here.
-- TimescaleDB partitions this automatically by recorded_at (7-day chunks).
-- Raw hourly data auto-expires after 6 months; daily aggregates are kept indefinitely.
CREATE TABLE environmental_snapshot (
    recorded_at             TIMESTAMPTZ      NOT NULL,
    user_id                 VARCHAR(64)      NOT NULL DEFAULT 'ashoo-user',

    -- Location context (location_id mirrors saved_location.id by convention)
    location_id             BIGINT,
    latitude                DOUBLE PRECISION,
    longitude               DOUBLE PRECISION,
    city_name               VARCHAR(255),

    -- Air quality (µg/m³)
    pm25                    DOUBLE PRECISION,   -- Fine particulate — strongest asthma trigger
    pm10                    DOUBLE PRECISION,   -- Coarse particulate
    o3                      DOUBLE PRECISION,   -- Ozone — second-strongest asthma trigger
    no2                     DOUBLE PRECISION,   -- Nitrogen dioxide — most robust in multi-pollutant models
    so2                     DOUBLE PRECISION,   -- Sulphur dioxide — combustion-related
    co                      DOUBLE PRECISION,   -- Carbon monoxide

    -- Pollen (grains/m³) — Europe only via Open-Meteo CAMS model
    pollen_alder            DOUBLE PRECISION,
    pollen_birch            DOUBLE PRECISION,
    pollen_grass            DOUBLE PRECISION,   -- Strongest thunderstorm-asthma connection
    pollen_mugwort          DOUBLE PRECISION,
    pollen_olive            DOUBLE PRECISION,
    pollen_ragweed          DOUBLE PRECISION,

    -- Meteorological
    temperature_c           DOUBLE PRECISION,
    humidity_pct            DOUBLE PRECISION,   -- Relative humidity; above 50% = dust mite risk proxy
    pressure_msl_hpa        DOUBLE PRECISION,   -- Barometric pressure at mean sea level
    wind_speed_ms           DOUBLE PRECISION,
    wind_gusts_ms           DOUBLE PRECISION,

    -- Derived signals — computed at ingest and stored for query efficiency.
    -- Trading storage space (cheap) for query speed (valuable across months of history).
    pm25_rate_of_change     DOUBLE PRECISION,   -- Delta vs prior reading — spike detection
    pressure_drop_3h        DOUBLE PRECISION,   -- Pressure change over 3h window — storm proxy
    cumulative_pm25_24h     DOUBLE PRECISION,   -- Rolling 24h PM2.5 burden
    aqi_computed            INTEGER,            -- Computed from pm25 using EPA 2024 breakpoints
    thunderstorm_flag       BOOLEAN DEFAULT FALSE, -- Heuristic only — labeled clearly in all UI

    -- Provenance
    data_source             VARCHAR(32)  NOT NULL,           -- OPEN_METEO, OPENAQ, AIRNOW
    data_origin             VARCHAR(32)  NOT NULL DEFAULT 'REAL'  -- REAL or SEEDED_SYNTHETIC
);

-- Convert to a TimescaleDB hypertable, partitioned by recorded_at.
-- 7-day chunks balance partition overhead against query scan size for hourly data.
SELECT create_hypertable('environmental_snapshot', 'recorded_at',
    chunk_time_interval => INTERVAL '7 days');

-- Auto-drop raw hourly data older than 6 months.
-- Daily aggregates (created in V8) are kept indefinitely and are unaffected.
SELECT add_retention_policy('environmental_snapshot', INTERVAL '6 months');

-- Most common query pattern: all readings for a user in a time range
CREATE INDEX ON environmental_snapshot (user_id, recorded_at DESC);
-- Query pattern for per-location history used by the correlation engine
CREATE INDEX ON environmental_snapshot (user_id, location_id, recorded_at DESC);
