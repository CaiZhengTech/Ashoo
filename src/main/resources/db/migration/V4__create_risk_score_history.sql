-- Stores the computed Personal Risk Index (0-100) at each hourly scoring run.
-- Powers the "risk over time" trend chart in the dashboard.
-- 6-month rolling retention matches the raw snapshot table it is derived from.
CREATE TABLE risk_score_history (
    scored_at               TIMESTAMPTZ      NOT NULL,
    user_id                 VARCHAR(64)      NOT NULL DEFAULT 'ashoo-user',

    risk_score              DOUBLE PRECISION NOT NULL,  -- Raw weighted aggregate (0-100)
    risk_score_smoothed     DOUBLE PRECISION,           -- EWMA-smoothed score (λ=0.3)
    risk_label              VARCHAR(16),                -- Great / Moderate / Elevated / High / Severe
    alert_triggered         BOOLEAN          DEFAULT FALSE,

    -- Snapshot of factor contributions at scoring time.
    -- JSONB gives schema flexibility as new factors are added without a migration.
    -- Example: {"pm25": 72.3, "pollen_grass": 88.1, "humidity_pct": 45.0}
    factor_scores           JSONB,

    -- Data quality context displayed alongside the score
    confidence_level        VARCHAR(8),     -- LOW, MEDIUM, HIGH
    symptom_days_available  INTEGER         -- Symptom days in history at this scoring moment
);

SELECT create_hypertable('risk_score_history', 'scored_at',
    chunk_time_interval => INTERVAL '7 days');

SELECT add_retention_policy('risk_score_history', INTERVAL '6 months');

CREATE INDEX ON risk_score_history (user_id, scored_at DESC);
