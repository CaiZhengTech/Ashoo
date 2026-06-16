-- Pre-computes daily averages so the dashboard chart can answer
-- "what were conditions on each day" without scanning every hourly row.
-- TimescaleDB refreshes this view automatically as new data arrives.
-- The base table (environmental_snapshot) has a 6-month rolling window;
-- this aggregate is kept indefinitely, giving a permanent daily summary.
CREATE MATERIALIZED VIEW daily_environmental_avg
WITH (timescaledb.continuous) AS
SELECT
    user_id,
    location_id,
    time_bucket('1 day', recorded_at) AS day,
    AVG(pm25)                AS avg_pm25,
    AVG(o3)                  AS avg_o3,
    AVG(no2)                 AS avg_no2,
    AVG(pollen_grass)        AS avg_pollen_grass,
    AVG(pollen_birch)        AS avg_pollen_birch,
    AVG(pollen_ragweed)      AS avg_pollen_ragweed,
    AVG(humidity_pct)        AS avg_humidity,
    AVG(pressure_msl_hpa)    AS avg_pressure,
    MIN(pressure_msl_hpa)    AS min_pressure,
    MAX(aqi_computed)        AS max_aqi,
    BOOL_OR(thunderstorm_flag) AS had_thunderstorm_risk,
    COUNT(*)                 AS reading_count
FROM environmental_snapshot
GROUP BY user_id, location_id, time_bucket('1 day', recorded_at)
WITH NO DATA;

-- Keep the aggregate up to date: refresh hourly, covering data from the last 3 days.
-- end_offset of 1 hour avoids conflicts with in-progress inserts.
SELECT add_continuous_aggregate_policy('daily_environmental_avg',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
