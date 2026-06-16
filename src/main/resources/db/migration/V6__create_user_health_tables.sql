-- User's pre-registered medications.
-- The app NEVER suggests, populates, or modifies these entries.
-- The user enters their own medication names; the app only echoes them back in reminders.
CREATE TABLE medication (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    name        VARCHAR(255) NOT NULL,   -- User's own name (e.g., "Ventolin inhaler")
    med_type    VARCHAR(32)  NOT NULL,   -- INHALER, ANTIHISTAMINE, EPINEPHRINE,
                                         -- NASAL_SPRAY, EYE_DROPS, OTHER
    notes       TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- User-defined reminder rules: "When my risk score exceeds X, show me this note."
-- The user writes the note. The app only evaluates the condition and echoes the note.
-- The mandatory disclaimer is appended by ReminderEngine — it is never stored here.
CREATE TABLE reminder_rule (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    risk_score_threshold  DOUBLE PRECISION NOT NULL CHECK (risk_score_threshold BETWEEN 0 AND 100),
    user_note             TEXT         NOT NULL,   -- Written entirely by the user
    medication_id         BIGINT       REFERENCES medication(id),
    -- Optional time window: only alert between these hours (prevents 11pm alerts).
    -- NULL means always active.
    time_window_start     TIME,
    time_window_end       TIME,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Explicit consent to the advisory-only disclaimer.
-- No reminder or medication feature is accessible until a ConsentRecord exists.
-- We store the exact disclaimer text accepted so the record remains meaningful
-- if the disclaimer wording ever changes in a future version.
CREATE TABLE consent_record (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    consented_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    disclaimer_text TEXT         NOT NULL   -- The exact text the user accepted
);

-- Audit log of every symptom log edit that triggered a model recomputation.
-- Surfaced in the UI as "Your patterns were last updated on [date]" for transparency.
CREATE TABLE recalibration_event (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    triggered_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason              TEXT,        -- e.g., "symptom log edited: added location"
    symptom_log_id      BIGINT,      -- Which log entry was edited
    recomputation_ms    INTEGER      -- How long the recomputation took (for observability)
);
