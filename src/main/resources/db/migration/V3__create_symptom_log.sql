-- The label data for the correlation engine.
-- Without symptom logs, there is nothing to correlate against.
-- Kept indefinitely — this is the user's own health record.
CREATE TABLE symptom_log (
    id              BIGSERIAL,
    logged_at       TIMESTAMPTZ  NOT NULL,   -- The date+time the user experienced symptoms (user-editable)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),  -- When this row was inserted
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),  -- Updated on any edit
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',

    -- Severity 0 = no symptoms, 10 = severe episode.
    -- CLAUDE.md locks this at 0-10 (wider than the original 0-3 spec to give users nuance).
    severity        SMALLINT     NOT NULL CHECK (severity BETWEEN 0 AND 10),

    -- User's own free-text description — no forced taxonomy, quick to fill out.
    -- NOTE: these notes are intentionally excluded from the Claude API briefing prompt
    -- to avoid sending potentially sensitive personal text to an external API.
    notes           TEXT,

    -- Location where the symptom occurred (nullable — user may not remember)
    location_id     BIGINT,
    city_name       VARCHAR(255),  -- Denormalized for display without a join

    -- Array of medication IDs (references medication.id by convention)
    medications_used BIGINT[],

    -- REAL = user-entered; SEEDED_SYNTHETIC = generated for demo/testing
    data_origin     VARCHAR(32)  NOT NULL DEFAULT 'REAL'
);

-- Hypertable partitioned by the symptom date (when symptoms occurred, not when logged).
-- 30-day chunks suit the coarser-grained, lower-volume nature of this table.
SELECT create_hypertable('symptom_log', 'logged_at',
    chunk_time_interval => INTERVAL '30 days');

CREATE INDEX ON symptom_log (user_id, logged_at DESC);
