# Technical Design Document — Ashoo v1.0
### Personal Environmental Health Correlation Engine

**Status:** Approved for implementation
**Stack:** Java 21 · Spring Boot 3.5.x · TimescaleDB · React + Vite · Fly.io
**Developer:** Cai Zheng
**Last updated:** June 2026

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Database Schema](#3-database-schema)
4. [Module Designs](#4-module-designs)
5. [API Specification](#5-api-specification)
6. [Correlation Engine](#6-correlation-engine)
7. [Daily Briefing (AI Layer)](#7-daily-briefing-ai-layer)
8. [React Frontend](#8-react-frontend)
9. [Configuration & Environment](#9-configuration--environment)
10. [Testing Strategy](#10-testing-strategy)
11. [Deployment](#11-deployment)
12. [Build Milestones](#12-build-milestones)

---

## 1. System Overview

Ashoo is a **modular monolith** — one deployable JAR serving a REST API, backed by
TimescaleDB, with a React frontend served separately. Clean internal module boundaries
support future extraction into services if scale ever justifies it.

The system does five things continuously:

1. **Ingests** environmental data hourly from free public APIs
2. **Archives** every reading into a time-series database
3. **Accepts** user-logged symptom entries via REST API
4. **Correlates** symptom history against environmental history statistically
5. **Surfaces** a personalized risk score, daily briefing, and pattern-based reminders

### What makes this different from AirNow / IQAir / PurpleAir

Those apps tell you what the air is doing for everyone.
Ashoo tells you what the air does to **you**, calibrated to your logged history.
The intelligence is per-user statistical correlation, not population averages.

### V1 scope boundary

V1 is **single-user** (no auth, default user = "ashoo-user"). Multi-user is V2.
The `user_id` column exists on every domain table from day one (defaulted to
`"ashoo-user"`) so V2 multi-tenancy is a query filter, not a schema migration.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         React Frontend (Vite)                        │
│                                                                     │
│  Dashboard  │  Risk Score  │  Symptom Log  │  Locations  │  Demo   │
│  (factor    │  (0-100 PRI  │  (log entry   │  (saved +   │  Mode   │
│   breakdown)│   + color)   │   form)       │   search)   │  toggle │
└─────────────────────────────┬───────────────────────────────────────┘
                              │ HTTP (REST + JSON)
┌─────────────────────────────▼───────────────────────────────────────┐
│                      Ashoo Spring Boot Backend                       │
│                                                                     │
│ ┌─────────────┐ ┌──────────────┐ ┌─────────────┐ ┌─────────────┐  │
│ │  Ingestion  │ │ Correlation  │ │   Briefing  │ │  Reminder   │  │
│ │  Scheduler  │ │  Engine      │ │  (Claude    │ │  Engine     │  │
│ │  (virtual   │ │              │ │   API)      │ │             │  │
│ │   threads)  │ │  Normalizer  │ │             │ │  Consent    │  │
│ │             │ │  Correlator  │ │  Prompt     │ │  Guard      │  │
│ │  OpenMeteo  │ │  Threshold   │ │  Builder    │ │             │  │
│ │  OpenAQ     │ │  Scorer      │ │  Disclaimer │ │  Disclaimer │  │
│ │  AirNow     │ │              │ │  Injector   │ │  Guard      │  │
│ └──────┬──────┘ └──────┬───────┘ └──────┬──────┘ └──────┬──────┘  │
│        │               │                │               │          │
│        └───────────────▼────────────────▼───────────────▼          │
│                  ┌─────────────────────────┐                       │
│                  │      Storage Layer       │                       │
│                  │   (Spring Data JDBC)     │                       │
│                  └─────────────┬───────────┘                       │
│                                │                                   │
│ ┌──────────────────────────────▼──────────────────────────────┐    │
│ │                     REST API Layer                           │    │
│ │   (Spring MVC + springdoc-openapi → /swagger-ui.html)       │    │
│ └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│  Actuator: /actuator/health · /actuator/metrics                     │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ JDBC (PostgreSQL wire protocol)
              ┌────────────────▼─────────────────┐
              │         TimescaleDB               │
              │    (PostgreSQL 16 extension)      │
              │                                   │
              │  environmental_snapshot  ← hypertable (7-day chunks)
              │  symptom_log             ← hypertable (30-day chunks)
              │  risk_score_history      ← hypertable (7-day chunks)
              │  saved_location          ← regular table
              │  recent_search           ← regular table (last 10)
              │  medication              ← regular table
              │  reminder_rule           ← regular table
              │  consent_record          ← regular table
              │  correlation_result      ← regular table
              │  recalibration_event     ← regular table
              │  briefing_log            ← regular table
              └───────────────────────────────────┘

External APIs polled hourly (outbound, virtual threads):
  → https://api.open-meteo.com      (weather + AQ + pollen)
  → https://api.openaq.org          (ground-truth AQ, X-API-Key)
  → https://www.airnowapi.org       (US AQI fallback)
  → https://api.anthropic.com       (daily briefing generation)
```

### Why a modular monolith?

At single-user scale, inter-service network hops add latency with zero benefit.
Modules communicate via direct Java method calls — no serialization, no network
failure surface, trivially debuggable. When Ashoo scales to real users, the module
seams become service boundaries. Until then the monolith is the correct choice.

---

## 3. Database Schema

### Why TimescaleDB over plain PostgreSQL?

TimescaleDB IS PostgreSQL. It adds one capability: automatic time-based table
partitioning (called hypertables) so that queries like
"give me all PM2.5 readings from the last 30 days" scan only the relevant
partitions instead of every row ever stored. At our data volume (hourly readings
× ~20 factors × months) this matters for query speed. The driver, ORM, SQL syntax,
and Spring Data integration are 100% identical to plain Postgres.

### Flyway migrations (all schema changes in order, never edited after merge)

---

#### V1__create_extensions.sql
```sql
-- Enable TimescaleDB. Must run before any hypertable is created.
-- The CASCADE keyword ensures any dependent objects are also enabled.
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;
```

---

#### V2__create_environmental_snapshot.sql
```sql
-- The core time-series table. One row per hourly API poll.
-- Every column the correlation engine reads lives here.
-- TimescaleDB partitions this automatically by recorded_at.
CREATE TABLE environmental_snapshot (
    recorded_at             TIMESTAMPTZ      NOT NULL,
    user_id                 VARCHAR(64)      NOT NULL DEFAULT 'ashoo-user',

    -- Location context
    location_id             BIGINT,          -- FK to saved_location (nullable for legacy rows)
    latitude                DOUBLE PRECISION,
    longitude               DOUBLE PRECISION,
    city_name               VARCHAR(255),

    -- Air quality (µg/m³)
    pm25                    DOUBLE PRECISION,  -- Fine particulate — strongest asthma trigger
    pm10                    DOUBLE PRECISION,  -- Coarse particulate
    o3                      DOUBLE PRECISION,  -- Ozone — second-strongest asthma trigger
    no2                     DOUBLE PRECISION,  -- Nitrogen dioxide — traffic-related, most robust multi-pollutant
    so2                     DOUBLE PRECISION,  -- Sulphur dioxide
    co                      DOUBLE PRECISION,  -- Carbon monoxide

    -- Pollen (grains/m³, Europe only via Open-Meteo CAMS)
    pollen_alder            DOUBLE PRECISION,
    pollen_birch            DOUBLE PRECISION,
    pollen_grass            DOUBLE PRECISION,  -- Strongest thunderstorm-asthma connection
    pollen_mugwort          DOUBLE PRECISION,
    pollen_olive            DOUBLE PRECISION,
    pollen_ragweed          DOUBLE PRECISION,

    -- Meteorological
    temperature_c           DOUBLE PRECISION,
    humidity_pct            DOUBLE PRECISION,  -- Above 50% = dust mite risk proxy
    pressure_msl_hpa        DOUBLE PRECISION,  -- Barometric pressure at mean sea level
    wind_speed_ms           DOUBLE PRECISION,
    wind_gusts_ms           DOUBLE PRECISION,

    -- Derived signals (computed at ingest, stored for query efficiency)
    pm25_rate_of_change     DOUBLE PRECISION,  -- Delta vs prior reading (spike detection)
    pressure_drop_3h        DOUBLE PRECISION,  -- Pressure change over 3h window (storm proxy)
    cumulative_pm25_24h     DOUBLE PRECISION,  -- Rolling 24h PM2.5 burden
    aqi_computed            INTEGER,           -- Computed from pm25 using EPA 2024 breakpoints
    thunderstorm_flag       BOOLEAN DEFAULT FALSE, -- Heuristic only — label this clearly in UI

    -- Provenance
    data_source             VARCHAR(32)  NOT NULL, -- OPEN_METEO, OPENAQ, AIRNOW
    data_origin             VARCHAR(32)  NOT NULL DEFAULT 'REAL' -- REAL or SEEDED_SYNTHETIC
);

-- Convert to hypertable partitioned by recorded_at (7-day chunks)
SELECT create_hypertable('environmental_snapshot', 'recorded_at',
    chunk_time_interval => INTERVAL '7 days');

-- Retention policy: auto-drop raw hourly data older than 6 months
-- Daily aggregates (continuous view below) are kept indefinitely
SELECT add_retention_policy('environmental_snapshot', INTERVAL '6 months');

CREATE INDEX ON environmental_snapshot (user_id, recorded_at DESC);
CREATE INDEX ON environmental_snapshot (user_id, location_id, recorded_at DESC);
```

---

#### V3__create_symptom_log.sql
```sql
-- The label data for the correlation engine.
-- Without symptom logs, there is nothing to correlate against.
-- Kept indefinitely — it is the user's own health record.
CREATE TABLE symptom_log (
    id              BIGSERIAL,
    logged_at       TIMESTAMPTZ  NOT NULL,   -- User-set date+time of symptom occurrence
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),  -- When the row was inserted
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),  -- Updated on any edit
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',

    -- Core fields
    severity        SMALLINT     NOT NULL CHECK (severity BETWEEN 0 AND 10),
    notes           TEXT,        -- User's own free text — quick, no forced taxonomy
    location_id     BIGINT,      -- FK to saved_location (nullable — user may not remember)
    city_name       VARCHAR(255),-- Denormalized for display without a join

    -- Medications used this episode (array of medication IDs)
    medications_used BIGINT[],

    -- Provenance
    data_origin     VARCHAR(32)  NOT NULL DEFAULT 'REAL'
);

SELECT create_hypertable('symptom_log', 'logged_at',
    chunk_time_interval => INTERVAL '30 days');

CREATE INDEX ON symptom_log (user_id, logged_at DESC);
```

---

#### V4__create_risk_score_history.sql
```sql
-- Stores the computed Personal Risk Index (0-100) at each hourly scoring run.
-- Powers the "risk over time" chart in the dashboard.
-- 6-month rolling retention matches environmental_snapshot.
CREATE TABLE risk_score_history (
    scored_at               TIMESTAMPTZ      NOT NULL,
    user_id                 VARCHAR(64)      NOT NULL DEFAULT 'ashoo-user',

    risk_score              DOUBLE PRECISION NOT NULL,  -- Raw weighted aggregate
    risk_score_smoothed     DOUBLE PRECISION,           -- EWMA-smoothed (λ=0.3)
    risk_label              VARCHAR(16),                -- Great/Moderate/Elevated/High/Severe
    alert_triggered         BOOLEAN          DEFAULT FALSE,

    -- Factor contributions snapshot (JSONB for flexibility, no schema lock-in)
    -- e.g. {"pm25": 72.3, "pollen_grass": 88.1, "humidity_pct": 45.0}
    factor_scores           JSONB,

    -- Confidence at time of scoring
    confidence_level        VARCHAR(8),     -- LOW, MEDIUM, HIGH
    symptom_days_available  INTEGER         -- How many symptom days existed at scoring time
);

SELECT create_hypertable('risk_score_history', 'scored_at',
    chunk_time_interval => INTERVAL '7 days');

SELECT add_retention_policy('risk_score_history', INTERVAL '6 months');

CREATE INDEX ON risk_score_history (user_id, scored_at DESC);
```

---

#### V5__create_location_tables.sql
```sql
-- Pre-registered named locations (home, work, gym, etc.)
-- The engine pre-fetches environmental data for all active saved locations hourly.
CREATE TABLE saved_location (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    label       VARCHAR(128) NOT NULL,  -- User-given name: "Home", "Work", etc.
    city_name   VARCHAR(255) NOT NULL,
    county      VARCHAR(255),
    country     VARCHAR(128) NOT NULL DEFAULT 'US',
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,  -- Primary = used for daily briefing
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Ad-hoc location queries (not saved permanently, rolling last 10)
CREATE TABLE recent_search (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    city_name   VARCHAR(255) NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    searched_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Only keep the 10 most recent searches per user
-- (enforced via application logic on insert — delete oldest when count > 10)
```

---

#### V6__create_user_health_tables.sql
```sql
-- User's pre-registered medications (never app-suggested, always user-entered)
CREATE TABLE medication (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    name        VARCHAR(255) NOT NULL,  -- User's own name for this medication
    med_type    VARCHAR(32)  NOT NULL,  -- INHALER, ANTIHISTAMINE, EPINEPHRINE,
                                        -- NASAL_SPRAY, EYE_DROPS, OTHER
    notes       TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- User-defined reminder rules
-- "When risk score exceeds X, show me this note I wrote"
CREATE TABLE reminder_rule (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    risk_score_threshold  DOUBLE PRECISION NOT NULL CHECK (risk_score_threshold BETWEEN 0 AND 100),
    user_note             TEXT         NOT NULL,  -- Written by user, echoed back by app
    medication_id         BIGINT       REFERENCES medication(id),
    time_window_start     TIME,        -- Optional: only alert between these hours
    time_window_end       TIME,        -- e.g., 07:00 to 22:00 (no late-night alerts)
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Explicit consent record — required before any reminder feature is accessible
CREATE TABLE consent_record (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    consented_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    disclaimer_text TEXT         NOT NULL  -- Store the exact text accepted
);

-- Tracks when the user edits a log entry and triggers model recomputation
CREATE TABLE recalibration_event (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    triggered_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason              TEXT,        -- e.g., "symptom log edited: added location"
    symptom_log_id      BIGINT,      -- Which entry was edited
    recomputation_ms    INTEGER      -- How long the recomputation took
);
```

---

#### V7__create_correlation_and_briefing_tables.sql
```sql
-- Correlation results per factor (recomputed on demand, cached here)
-- Kept indefinitely — small rows, high value
CREATE TABLE correlation_result (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    computed_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    factor_name           VARCHAR(64)  NOT NULL,  -- e.g., "pm25", "pollen_grass"
    best_lag_hours        INTEGER,                 -- 0, 24, 48, or 72
    spearman_r            DOUBLE PRECISION,        -- -1.0 to 1.0
    point_biserial_r      DOUBLE PRECISION,        -- -1.0 to 1.0
    personal_threshold    DOUBLE PRECISION,        -- Raw units cut-point
    threshold_percentile  DOUBLE PRECISION,        -- Same threshold as 0-100 percentile
    weight                DOUBLE PRECISION,        -- Normalized importance weight
    confidence_level      VARCHAR(8),              -- LOW, MEDIUM, HIGH
    symptom_days_used     INTEGER,
    total_days_used       INTEGER,
    -- Days where score was high but user felt fine (model transparency)
    mismatch_count        INTEGER DEFAULT 0
);

-- Log of every daily briefing generated (for audit + debugging)
CREATE TABLE briefing_log (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL DEFAULT 'ashoo-user',
    generated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    risk_score      DOUBLE PRECISION,
    risk_label      VARCHAR(16),
    briefing_text   TEXT         NOT NULL,  -- Full text returned by Claude API
    tokens_used     INTEGER,                -- For cost tracking
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE
);
```

---

#### V8__create_continuous_aggregate.sql
```sql
-- Pre-computes daily averages so the dashboard chart loads fast
-- without scanning every hourly row. TimescaleDB refreshes this
-- automatically as new data arrives.
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
GROUP BY user_id, location_id, time_bucket('1 day', recorded_at);

-- Refresh policy: keep the aggregate up to date automatically
SELECT add_continuous_aggregate_policy('daily_environmental_avg',
    start_offset => INTERVAL '3 days',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
```

---

## 4. Module Designs

### 4.1 Ingestion Module

**Package:** `com.ashoo.ingestion`

Handles all outbound API calls. Uses virtual threads for concurrent polling.
Every piece of data written here is what the correlation engine later reads.

```java
/**
 * Triggers the full environmental ingestion cycle on a fixed hourly schedule.
 *
 * Uses fixedDelay (not fixedRate) to ensure one cycle fully completes before
 * the next begins — preventing overlapping polls if an API call is slow.
 *
 * With spring.threads.virtual.enabled=true, Spring runs this on a virtual thread.
 * Virtual threads are extremely cheap (microseconds to create vs milliseconds for
 * OS threads), making them ideal for I/O-bound tasks like HTTP polling.
 */
@Scheduled(fixedDelay = 3_600_000)
public void runIngestionCycle() { ... }

/**
 * Fans out concurrent HTTP calls to Open-Meteo for all active saved locations.
 *
 * Uses Executors.newVirtualThreadPerTaskExecutor() to spawn one virtual thread
 * per location. Because virtual threads are so lightweight, spawning 10-20
 * concurrent threads here costs almost no memory — unlike OS threads, where
 * each consumes ~1MB of stack space.
 *
 * We do NOT use StructuredTaskScope here (still preview in Java 25) — instead
 * we use a standard ExecutorService with a CompletableFuture.allOf() join,
 * which is stable and well-understood.
 *
 * @param locations  list of saved locations to poll concurrently
 * @return           list of raw API responses, one per location
 */
public List<OpenMeteoResponse> fetchAllLocationsConcurrently(
        List<SavedLocation> locations) { ... }

/**
 * Computes all derived signals from a raw reading and its predecessor.
 *
 * Derived signals are computed once at ingest and stored, rather than
 * recomputed on every query. This trades storage space (cheap) for
 * query speed (valuable when the correlation engine scans months of history).
 *
 * The thunderstorm_flag is a HEURISTIC, not a validated predictor.
 * It must be labeled clearly in any UI that surfaces it.
 *
 * @param current   the freshly-ingested reading
 * @param previous  the prior reading for this location (for delta computation),
 *                  or empty if this is the first reading ever
 * @return          the same snapshot with derived fields populated
 */
public EnvironmentalSnapshot computeDerivedSignals(
        EnvironmentalSnapshot current,
        Optional<EnvironmentalSnapshot> previous) { ... }

/**
 * Acquires a rate-limit permit before each outbound API call.
 *
 * Uses a Semaphore initialized to the per-minute limit (55 for OpenAQ,
 * leaving 5 as buffer against the hard 60/min limit). A scheduled task
 * releases all permits every 60 seconds, refilling the bucket.
 *
 * This is the token-bucket pattern — standard for API rate limiting.
 * Without this, concurrent virtual threads would instantly exhaust the
 * OpenAQ quota and receive HTTP 429 responses.
 *
 * @param apiName  identifier for logging (e.g., "openaq", "open-meteo")
 * @throws InterruptedException if the thread is interrupted while waiting
 */
public void acquirePermit(String apiName) throws InterruptedException { ... }
```

### 4.2 AQI Calculator

**Package:** `com.ashoo.common`

```java
/**
 * Computes the EPA Air Quality Index from a raw PM2.5 concentration.
 *
 * Uses the verified post-May 6 2024 EPA breakpoints, which changed the
 * "Good" ceiling from 12.0 to 9.0 µg/m³. Using the old breakpoints
 * would understate risk for values between 9.1–12.0 µg/m³.
 *
 * Formula: I_p = ((I_Hi - I_Lo) / (BP_Hi - BP_Lo)) * (C_p - BP_Lo) + I_Lo
 * Where I = AQI index value, BP = breakpoint concentration, C_p = input concentration.
 * PM2.5 concentrations are truncated to one decimal place before computation.
 *
 * @param pm25  raw PM2.5 concentration in µg/m³
 * @return      computed AQI integer (0–500), or -1 if input is null/negative
 */
public static int computeFromPm25(Double pm25) { ... }

/**
 * Maps a Personal Risk Index (0-100) to the Ashoo risk label and display color.
 *
 * The PRI is DIFFERENT from AQI. AQI is a population-average standard.
 * PRI is personalized: an 80 PRI means "conditions match YOUR worst recorded days,"
 * not that the air is objectively dangerous for everyone.
 * This distinction must be communicated clearly in the UI.
 *
 * @param pri  Personal Risk Index value (0.0–100.0)
 * @return     RiskLevel enum with label, color code, and action guidance
 */
public static RiskLevel toRiskLevel(double pri) { ... }
```

### 4.3 Correlation Module

**Package:** `com.ashoo.correlation`

The statistical heart of Ashoo. All logic here is honest statistics —
transparent, explainable, and conservative when data is sparse.

```java
/**
 * Converts a raw environmental measurement to a personalized 0-100 score
 * by computing its percentile rank within the user's own historical distribution.
 *
 * Why percentile rank over min-max normalization?
 * Min-max is sensitive to outliers: one extreme reading compresses all others
 * toward zero, making the score meaningless day-to-day. Percentile rank is
 * robust: "today's PM2.5 is at the 87th percentile of what YOU have seen"
 * is meaningful regardless of outliers and works across different units
 * (µg/m³, grains/m³, hPa) without any conversion factor.
 *
 * @param currentValue     the raw measurement in its native units
 * @param historicalValues all past raw values for this factor for this user
 * @return                 percentile rank between 0.0 and 100.0
 */
public double normalize(double currentValue, List<Double> historicalValues) { ... }

/**
 * Computes Spearman rank correlation between a factor time-series and
 * symptom severity scores at a given time lag.
 *
 * Why Spearman, not Pearson?
 * Pearson assumes both variables are normally distributed. Environmental data
 * and symptom scores are typically skewed (many low-pollution days, few high
 * ones). Spearman works on ranks, making it robust to non-normal distributions
 * and extreme values — much better suited to health and environmental data.
 *
 * Why multiple lags?
 * Asthma symptoms often lag exposure by hours or days. Inhaling high-pollen
 * air at 8am may not produce symptoms until afternoon (lag ~8h) or the next
 * morning (lag ~24h). We test 0, 24, 48, and 72-hour lags and report
 * the one where correlation is strongest.
 *
 * Returns NaN if there are fewer than MIN_PAIRS (10) aligned data points,
 * signaling to the caller that results are unreliable.
 *
 * @param factorValues   chronological list of hourly factor readings
 * @param symptomScores  daily severity scores (0-10) aligned by day
 * @param lagHours       time offset to apply: 0, 24, 48, or 72
 * @return               Spearman's rho (-1.0 to 1.0), or Double.NaN if insufficient data
 */
public double computeSpearmanAtLag(List<Double> factorValues,
                                    List<Integer> symptomScores,
                                    int lagHours) { ... }

/**
 * Finds the personal threshold for a factor that best separates
 * the user's symptom days from their symptom-free days.
 *
 * Uses Youden's J statistic: J = Sensitivity + Specificity - 1.
 * We test every candidate cut-point in the data and pick the one
 * that maximizes J. This is the standard medical-statistics method
 * for choosing a diagnostic cut-point, adapted here for personal
 * environmental health patterns.
 *
 * Sparse-data fallback: when fewer than MIN_SYMPTOM_DAYS (10) symptom
 * days are available, Youden's J is unreliable (too few positives to
 * estimate sensitivity). In this case we fall back to the 75th percentile
 * of the user's symptom-day readings as an estimated threshold,
 * labeled CONFIDENCE_LOW. This is honest and still useful.
 *
 * The returned ThresholdResult always includes a confidence level
 * and the number of symptom days used, so the UI can show:
 * "Threshold estimated from 6 symptom days (low confidence — keep logging)"
 *
 * @param onSymptomDays    factor readings on days the user logged symptoms (severity >= 1)
 * @param onNonSymptomDays factor readings on days with no logged symptoms
 * @return                 ThresholdResult with cut-point, confidence level, and J statistic
 */
public ThresholdResult findPersonalThreshold(List<Double> onSymptomDays,
                                              List<Double> onNonSymptomDays) { ... }

/**
 * Computes the current Personal Risk Index (0-100) from normalized factor scores.
 *
 * Aggregation: weighted sum where each factor's weight is the absolute value of
 * its Spearman correlation with the user's symptoms. Factors with no significant
 * correlation get near-zero weight. Weights are normalized to sum to 1.0.
 *
 * EWMA smoothing (λ=0.3):
 *   smoothed_t = 0.3 * raw_t + 0.7 * smoothed_{t-1}
 * This reduces hour-to-hour noise without losing responsiveness to real events.
 * λ=0.3 means recent readings carry 30% of the weight; recent history carries 70%.
 *
 * Hysteresis prevents "flapping" (score toggling on/off at the boundary):
 *   Alert fires when smoothed score crosses ABOVE 70
 *   Alert clears when smoothed score falls BELOW 55
 * Once triggered, conditions must meaningfully improve before the alert clears.
 *
 * @param normalizedScores     map of factor name → percentile score (0-100)
 * @param factorWeights        map of factor name → importance weight (sums to 1.0)
 * @param previousSmoothed     EWMA score from the prior hour (0.0 if first run)
 * @param previousAlertActive  whether an alert was active in the prior hour
 * @return                     RiskScoreResult with raw score, smoothed score, alert state
 */
public RiskScoreResult computeScore(Map<String, Double> normalizedScores,
                                     Map<String, Double> factorWeights,
                                     double previousSmoothed,
                                     boolean previousAlertActive) { ... }

/**
 * Identifies days where the predicted risk score was high but the user
 * logged no symptoms — model "mismatches" that reveal what the engine is missing.
 *
 * Mismatch transparency is a key Ashoo differentiator. Instead of hiding
 * model failures, we surface them explicitly: "On Oct 3, your score was 85
 * but you felt fine. On Oct 7, your score was 42 but you had a severe episode."
 *
 * These mismatches help the user understand the model's limitations and
 * may reveal factors we are not yet measuring (indoor triggers, stress,
 * exercise intensity). The user can use this view to recalibrate log entries
 * they may have entered incorrectly.
 *
 * @param userId    the user whose history to analyze
 * @param lookback  how far back to search (e.g., INTERVAL '90 days')
 * @return          list of MismatchDay records, ordered by score discrepancy descending
 */
public List<MismatchDay> findMismatchDays(String userId, Duration lookback) { ... }
```

### 4.4 Briefing Module

**Package:** `com.ashoo.briefing`

Generates the daily plain-English summary using the Anthropic Claude API.
This is the most safety-critical module besides the reminder system.

```java
/**
 * Assembles the structured context object that will be sent to the Claude API.
 *
 * We do NOT send raw database rows to the AI. We build a controlled, structured
 * BriefingContext object that contains only what the briefing needs:
 * - Current risk score and label
 * - Top 3 contributing factors and their normalized scores
 * - User's 7-day symptom history (severity + medication used, no free text)
 * - User's registered medication types (not names — privacy)
 * - User's primary location name
 * - Data confidence level
 *
 * Excluding the user's free-text symptom notes from the AI prompt is intentional:
 * they may contain sensitive personal information the user did not intend to share
 * with an external API. We use only structured fields.
 *
 * @param userId  the user for whom to build the briefing context
 * @return        BriefingContext ready to be serialized into the Claude API prompt
 */
public BriefingContext buildContext(String userId) { ... }

/**
 * Calls the Anthropic Claude API with the briefing context and returns
 * a plain-English daily summary paragraph.
 *
 * The system prompt enforces all safety constraints programmatically —
 * the model is instructed to use hedged language, never name specific
 * medications in dosing context, and always end with the disclaimer.
 * These are enforced at the prompt level, not trusted to model judgment alone.
 *
 * The generated briefing is logged to briefing_log for audit purposes.
 * If the API call fails (network error, rate limit), a fallback template
 * briefing is returned so the user always sees something useful.
 *
 * @param context   the structured briefing context
 * @param isDemo    whether this is a demo persona briefing (logged separately)
 * @return          BriefingResult with the generated text and token count
 * @throws BriefingException if the API returns an error and fallback also fails
 */
public BriefingResult generateBriefing(BriefingContext context, boolean isDemo) { ... }

/**
 * Injects the mandatory disclaimer into the briefing text.
 *
 * This runs as a post-processing step AFTER the Claude API response,
 * ensuring the disclaimer is always present regardless of what the model
 * generates. It is not optional and cannot be disabled by configuration.
 *
 * The disclaimer text is defined as a constant in this class —
 * it must never be loaded from config or a database, because that would
 * allow it to be changed at runtime without a code review.
 *
 * @param briefingText  the raw text returned by the Claude API
 * @return              the briefing text with the disclaimer appended
 */
public String injectDisclaimer(String briefingText) { ... }
```

**The system prompt (hardcoded constant — never loaded from config):**
```
You are Ashoo's daily briefing assistant. You generate a single paragraph
(3-5 sentences) summarizing today's environmental conditions for a user
with respiratory allergies, based on their personal historical patterns.

Rules you must follow without exception:
1. Use hedged language: "historically," "similar to," "may," "tend to"
2. Never say "take [medication]" or specify any dose or frequency
3. Never claim to diagnose, predict with certainty, or use clinical language
4. If confidence is LOW, include "you're still early — keep logging for better insights"
5. Always end your response with exactly this sentence:
   "As always, consult your doctor for medical decisions."
6. Keep it friendly, clear, and under 100 words
7. Do not use markdown formatting
```

### 4.5 Reminder Module

**Package:** `com.ashoo.reminder`

```java
/**
 * Verifies that the user has explicitly accepted the advisory disclaimer
 * before any reminder or medication feature is accessible.
 *
 * This is enforced as a guard at the service layer, not just the API layer,
 * so that no internal code path can bypass it. The FDA General Wellness
 * guidance (Jan 6, 2026) requires wellness apps to make clear they are not
 * medical devices. Storing explicit timestamped consent is the implementation
 * of that requirement.
 *
 * @param userId  the user to check
 * @throws ConsentRequiredException if no ConsentRecord exists for this user
 */
public void requireConsent(String userId) { ... }

/**
 * Evaluates the user's active reminder rules against the current risk score
 * and current time, returning any triggered reminders.
 *
 * Time-awareness: rules with time_window_start/end set are only evaluated
 * when the current local time falls within that window. This prevents
 * late-night alerts when the user is asleep and conditions can't be acted on.
 *
 * The mandatory disclaimer is appended to every reminder unconditionally.
 * It is defined as a constant — it cannot be overridden by configuration,
 * user settings, or any other mechanism.
 *
 * This method only ECHOES what the user already wrote in their reminder_rule.
 * It adds zero content of its own beyond the disclaimer.
 *
 * @param userId        the user whose rules to evaluate
 * @param currentScore  the current smoothed Personal Risk Index (0.0–100.0)
 * @param currentTime   the current local time (for time-window filtering)
 * @return              list of triggered reminders with disclaimer; empty if none triggered
 * @throws ConsentRequiredException if the user has not accepted the disclaimer
 */
public List<ReminderResult> evaluateReminders(String userId,
                                               double currentScore,
                                               LocalTime currentTime) { ... }
```

---

## 5. API Specification

**Base path:** `/api/v1`
**Content-Type:** `application/json`
**Auto-docs:** `GET /swagger-ui.html`
**Auth:** None in V1 (single user). V2 adds Spring Security + JWT.

### Ingestion

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/ingestion/trigger` | Manually trigger ingestion cycle |
| `POST` | `/ingestion/seed-history` | Backfill real historical data from Open-Meteo |
| `POST` | `/ingestion/seed-demo` | Seed all three demo personas with synthetic data |

### Environmental snapshots

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/snapshots/latest` | Most recent snapshot for primary location |
| `GET` | `/snapshots?from=&to=&locationId=` | Snapshots in a time range |
| `GET` | `/snapshots/daily-averages?from=&to=` | Daily aggregates from continuous view |
| `GET` | `/conditions?lat=&lon=` | On-demand conditions for any coordinates |
| `GET` | `/conditions?city=` | On-demand conditions by city name |

### Symptom log

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/symptoms` | Log a symptom entry |
| `GET` | `/symptoms?from=&to=` | Retrieve log for a date range |
| `PUT` | `/symptoms/{id}` | Edit a past entry (triggers recomputation) |
| `DELETE` | `/symptoms/{id}` | Delete an entry |

### Risk & correlation

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/risk/current` | Current Personal Risk Index with factor breakdown |
| `GET` | `/risk/history?from=&to=` | PRI over time (for chart) |
| `POST` | `/correlation/compute` | Recompute all correlations from full history |
| `GET` | `/correlation/results` | Per-factor correlation results + thresholds |
| `GET` | `/correlation/mismatches` | Days where score mismatched logged symptoms |

### Briefing

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/briefing/today` | Generate or retrieve today's daily briefing |

### Locations

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/locations` | Add a saved location |
| `GET` | `/locations` | List saved locations |
| `PUT` | `/locations/{id}` | Update a saved location |
| `DELETE` | `/locations/{id}` | Remove a saved location |
| `GET` | `/locations/recent-searches` | Last 10 ad-hoc searches |

### Medications & reminders

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/consent` | Accept the advisory disclaimer |
| `GET` | `/consent` | Check consent status |
| `POST` | `/medications` | Register a medication |
| `GET` | `/medications` | List medications |
| `DELETE` | `/medications/{id}` | Remove a medication |
| `POST` | `/reminder-rules` | Create a reminder rule |
| `GET` | `/reminder-rules` | List active rules |
| `DELETE` | `/reminder-rules/{id}` | Remove a rule |
| `GET` | `/reminders/current` | Evaluate and return triggered reminders now |

### Export & demo

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/export?from=&to=` | Export symptom log + daily conditions as CSV |
| `GET` | `/demo/profiles` | List the three demo persona profiles |
| `POST` | `/demo/activate/{persona}` | Switch to a demo persona (alex/jordan/morgan) |

### Example response — `GET /risk/current`

```json
{
  "userId": "ashoo-user",
  "scoredAt": "2025-09-15T14:00:00Z",
  "location": "Sharon, MA",
  "riskScore": 74.2,
  "riskScoreSmoothed": 68.9,
  "riskLabel": "High",
  "riskColor": "#EF4444",
  "alertTriggered": false,
  "confidenceLevel": "MEDIUM",
  "dataNote": "Based on 47 days of history including 14 symptom days.",
  "factorBreakdown": [
    {
      "factor": "pm25",
      "rawValue": 18.3,
      "unit": "µg/m³",
      "percentileScore": 71.0,
      "weight": 0.28,
      "personalThreshold": 16.1,
      "aboveThreshold": true
    },
    {
      "factor": "pollen_grass",
      "rawValue": 42.1,
      "unit": "grains/m³",
      "percentileScore": 83.0,
      "weight": 0.35,
      "personalThreshold": 38.0,
      "aboveThreshold": true
    },
    {
      "factor": "humidity_pct",
      "rawValue": 67.0,
      "unit": "%",
      "percentileScore": 72.0,
      "weight": 0.21,
      "personalThreshold": 62.0,
      "aboveThreshold": true
    }
  ],
  "attribution": "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)"
}
```

---

## 6. Correlation Engine

### Full computation pipeline (runs every hour after ingestion)

```
Hourly (triggered by IngestionScheduler after snapshot is saved):
  1. Load most recent snapshot for each saved location
  2. PercentileNormalizer → normalize each factor 0-100 using user's history
  3. RiskScorer:
       a. Weighted sum using current factor weights from correlation_result
       b. Apply EWMA (λ=0.3) using prior hour's smoothed score
       c. Apply hysteresis (alert-on:70, alert-off:55)
  4. Save to risk_score_history
  5. ReminderEngine → evaluate active rules against new score + current time
  6. If score changed label (e.g., Moderate → High), generate new briefing

On demand (POST /correlation/compute, or auto-triggered after every 5th symptom log edit):
  1. Fetch all environmental_snapshot rows (chronological, up to 6 months)
  2. Fetch all symptom_log rows (align by day — use daily average for multi-reading days)
  3. For each factor × each lag (0/24/48/72h):
       a. SpearmanCorrelator → rho at this lag
       b. PointBiserialCorrelator → rpb (binary symptom vs continuous factor)
  4. For each factor:
       a. Split into symptom-day values vs non-symptom-day values
       b. YoudenThresholdFinder → personal cut-point + confidence
       c. Update correlation_result row
  5. Normalize weights = |rho| / sum(|rho| for all factors)
  6. Log RecalibrationEvent
```

### Confidence levels

| Label | Symptom Days | What it means to the user |
|-------|-------------|---------------------------|
| LOW | < 10 | "Estimates only — keep logging" |
| MEDIUM | 10–29 | "Patterns emerging — thresholds improving" |
| HIGH | 30+ | "Strong personal baseline established" |

### Synthetic demo data seeding

For each demo persona (Alex / Jordan / Morgan):
1. Backfill 90 days of REAL weather + AQ from Open-Meteo for Amsterdam
2. Generate SYNTHETIC symptom logs correlated with the real data:
   - **Alex (low risk):** symptoms triggered only when pm25 > 25 AND humidity > 70%
     simultaneously (strict AND). Severity capped at 4.
   - **Jordan (moderate):** symptoms when pm25 > 18 OR pollen_grass > 30.
     Severity 3–7. Clear seasonal pattern.
   - **Morgan (high risk):** symptoms when pm25 > 12 OR pollen > 15 OR humidity > 60%.
     Severity frequently 7–10. Shows mismatch days (very sensitive, non-obvious triggers).
3. All synthetic rows: `data_origin = 'SEEDED_SYNTHETIC'`
4. Every API response and UI label marks demo data clearly

---

## 7. Daily Briefing (AI Layer)

**Model:** `claude-sonnet-4-6` (Anthropic API)
**Trigger:** Once per day per user (cached in briefing_log, regenerated if stale)
**Fallback:** Template string if API unavailable

```java
// The prompt is assembled in BriefingPromptBuilder
// Context sent to the API (structured, no raw user text):
{
  "riskScore": 74,
  "riskLabel": "High",
  "topFactors": [
    {"name": "Grass pollen", "percentile": 83, "abovePersonalThreshold": true},
    {"name": "PM2.5", "percentile": 71, "abovePersonalThreshold": true},
    {"name": "Humidity", "percentile": 72, "abovePersonalThreshold": true}
  ],
  "recentSymptomDays": [
    {"daysAgo": 2, "severity": 7, "medicationsUsed": ["INHALER"]},
    {"daysAgo": 5, "severity": 4, "medicationsUsed": ["ANTIHISTAMINE"]}
  ],
  "registeredMedicationTypes": ["INHALER", "ANTIHISTAMINE"],
  "location": "Sharon, MA",
  "confidence": "MEDIUM",
  "symptomDaysAvailable": 14
}
```

**Example output:**
> "Today looks high-risk for you in Sharon — grass pollen is at its highest
> in over two months, and humidity is elevated, a combination that has preceded
> your moderate-to-severe symptom days historically. You logged a severity-7
> episode just two days ago under similar conditions. You have an inhaler
> registered for days like today. As always, consult your doctor for medical decisions."

---

## 8. React Frontend

**Stack:** React 18 + Vite + Tailwind CSS + Recharts (charts) + React Query (API fetching)

### Page structure

```
/                   → Dashboard (default view)
/log                → Log a symptom entry
/history            → Symptom log history + mismatch view
/correlation        → Factor correlation results + thresholds
/locations          → Saved locations + recent searches
/conditions         → On-demand location query (interactive for recruiters)
/medications        → Medication list + reminder rules
/export             → Download CSV
/demo               → Demo mode — select a persona
```

### Dashboard components (priority order for build)

1. **RiskScoreBadge** — large 0-100 number with color background + label
2. **DailyBriefingCard** — today's briefing text + disclaimer
3. **FactorBreakdownList** — each factor with its percentile bar + threshold indicator
4. **RiskOverTimeChart** — 30-day PRI trend line (Recharts LineChart)
5. **ActiveRemindersCard** — triggered reminders with disclaimer
6. **ConditionSearchBox** — type any city, get conditions + "how risky for me?" score

### Demo mode UX

- Toggle in the header: "Demo Mode: [Alex ▾]" dropdown
- Banner at top: "You are viewing demo data for Alex (low sensitivity). Your real data is safe."
- All demo API calls include `?demo=alex` query param
- Demo personas have realistic variation including mismatch days

---

## 9. Configuration & Environment

### application.yml
```yaml
spring:
  application:
    name: ashoo
  threads:
    virtual:
      enabled: true   # The single most important modern Java config in this app
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ashoo}
    username: ${DB_USER:ashoo}
    password: ${DB_PASSWORD:ashoo}
  flyway:
    enabled: true
    locations: classpath:db/migration

ashoo:
  default-location:
    latitude: 42.1237
    longitude: -71.1847
    city: "Sharon, MA"
    country: "US"
  demo-location:       # European city for pollen data availability
    latitude: 52.3676
    longitude: 4.9041
    city: "Amsterdam, Netherlands"
  polling:
    interval-ms: 3600000
  openaq:
    api-key: ${OPENAQ_API_KEY}
    base-url: https://api.openaq.org
    rate-limit-per-minute: 55
  airnow:
    api-key: ${AIRNOW_API_KEY}
    base-url: https://www.airnowapi.org
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-6
    max-tokens: 300
  correlation:
    min-symptom-days: 10
    lag-windows-hours: [0, 24, 48, 72]
    ewma-lambda: 0.3
    alert-on-threshold: 70.0
    alert-off-threshold: 55.0
  retention:
    raw-snapshots: 6 months
    risk-scores: 6 months

management:
  endpoints:
    web:
      exposure:
        include: health, metrics, info

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
```

### Environment variables

| Variable | Description | Where to get it |
|----------|-------------|----------------|
| `DB_HOST` | Postgres host | `localhost` in dev, Fly.io Postgres in prod |
| `DB_PASSWORD` | Database password | Generate securely |
| `OPENAQ_API_KEY` | OpenAQ v3 key | explore.openaq.org/register (free) |
| `AIRNOW_API_KEY` | AirNow API key | docs.airnowapi.org (free) |
| `ANTHROPIC_API_KEY` | Claude API key | console.anthropic.com |

### docker-compose.yml
```yaml
version: '3.9'
services:
  timescaledb:
    image: timescale/timescaledb:latest-pg16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: ashoo
      POSTGRES_USER: ashoo
      POSTGRES_PASSWORD: ashoo
    volumes:
      - timescale_data:/var/lib/postgresql/data

  ashoo-backend:
    build: .
    ports: ["8080:8080"]
    environment:
      DB_HOST: timescaledb
      DB_PASSWORD: ashoo
      OPENAQ_API_KEY: ${OPENAQ_API_KEY}
      AIRNOW_API_KEY: ${AIRNOW_API_KEY}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    depends_on: [timescaledb]

volumes:
  timescale_data:
```

---

## 10. Testing Strategy

### Why Testcontainers (not H2)?

H2 is an in-memory database that does not support TimescaleDB features.
`create_hypertable()`, continuous aggregates, and retention policies
simply do not exist in H2. Tests against H2 would pass while the real
database fails. Testcontainers spins up a real TimescaleDB Docker container
per test run — true production fidelity in CI at the cost of a slightly
longer test run time. Worth it every time.

### Test layers

**Unit tests (no DB, no network — fast, run constantly):**
```
PercentileNormalizerTest    → verify normalization math edge cases
SpearmanCorrelatorTest      → verify rho at each lag, including NaN when sparse
YoudenThresholdFinderTest   → verify Youden's J + sparse fallback
AqiCalculatorTest           → verify every EPA 2024 PM2.5 breakpoint exactly
RiskScorerTest              → verify EWMA + hysteresis behavior
ReminderEngineTest          → verify disclaimer always present, time-window filtering
BriefingDisclaimerTest      → verify disclaimer injected even if Claude API omits it
ConsentGuardTest            → verify all reminder paths throw without consent
```

**Integration tests (Testcontainers + real TimescaleDB — slower, run in CI):**
```
FlywayMigrationTest         → all migrations run cleanly, all tables exist
IngestionIntegrationTest    → mock Open-Meteo response → verify DB row written correctly
CorrelationIntegrationTest  → seed synthetic history → run compute → verify top factors recovered
MismatchDetectionTest       → seed known mismatch days → verify findMismatchDays returns them
ReminderIntegrationTest     → create consent + rule → trigger score → verify reminder fires
ExportIntegrationTest       → seed data → call /export → parse CSV → verify row count
ApiIntegrationTest          → full HTTP round-trip via Spring MockMvc
```

### GitHub Actions CI
```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run all tests (Testcontainers pulls TimescaleDB automatically)
        run: ./gradlew test
      - name: Build production JAR
        run: ./gradlew bootJar
```

---

## 11. Deployment

### Dockerfile
```dockerfile
# Build stage — compile with full JDK
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Runtime stage — lean JRE only (smaller image)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Fly.io setup
```bash
fly auth login
fly launch --name ashoo-backend --region iad
fly secrets set OPENAQ_API_KEY=your-key
fly secrets set AIRNOW_API_KEY=your-key
fly secrets set ANTHROPIC_API_KEY=your-key
fly secrets set DB_PASSWORD=your-password
fly postgres create --name ashoo-db
fly postgres attach ashoo-db
fly deploy
```

### Frontend deployment (Vercel — free tier)
```bash
cd frontend
npm run build
# Push to GitHub → Vercel auto-deploys on push
# Set VITE_API_BASE_URL=https://ashoo-backend.fly.dev/api/v1
```

---

## 12. Build Milestones

Work in order. Do not start a milestone before the previous one has CI passing.
Each milestone produces a working, demonstrable system — not a half-built one.

---

### Milestone 1 — Foundation (Days 1–2)
**Goal:** Spring Boot connects to TimescaleDB. All schema exists. CI is green.

- [ ] Initialize project: Spring Web, Spring Data JDBC, Flyway, Actuator, springdoc-openapi, Testcontainers
- [ ] Write Flyway migrations V1–V8
- [ ] Testcontainers test: migrations run, all tables + hypertables exist, continuous aggregate created
- [ ] `GET /actuator/health` → `{"status":"UP"}`
- [ ] `GET /swagger-ui.html` renders
- [ ] GitHub Actions CI set up — must be green on first push

**Definition of done:** CI green. TimescaleDB schema complete. Swagger accessible.

---

### Milestone 2 — Ingestion + AQI (Days 3–5)
**Goal:** Hourly poller runs. Real data lands in the database.

- [ ] AqiCalculator with EPA 2024 breakpoints + unit tests for every breakpoint
- [ ] OpenMeteoClient (weather + AQ + pollen in one call)
- [ ] Derived signal computation (rate-of-change, pressure drop, thunderstorm flag) + unit tests
- [ ] ApiRateLimiter with Semaphore + unit test
- [ ] IngestionScheduler with virtual threads (`@Scheduled` + `SimpleAsyncTaskScheduler`)
- [ ] `POST /ingestion/trigger` for manual test triggering
- [ ] `GET /snapshots/latest` and `GET /snapshots?from=&to=`
- [ ] `GET /conditions?lat=&lon=` and `GET /conditions?city=`
- [ ] Integration test: trigger → mock Open-Meteo → verify row in DB with correct derived signals
- [ ] `POST /ingestion/seed-history` (Open-Meteo historical backfill)

**Definition of done:** CI green. Manual trigger writes a row. `/conditions?city=Amsterdam` returns real data.

---

### Milestone 3 — Locations + Symptom Log (Days 5–7)
**Goal:** User can register locations and log symptom days.

- [ ] SavedLocation CRUD: `POST/GET/PUT/DELETE /locations`
- [ ] Recent search: auto-save on `/conditions?city=`, rolling last 10
- [ ] SymptomLog CRUD: `POST/GET/PUT/DELETE /symptoms`
- [ ] Validation: severity 0–10, logged_at required
- [ ] Edit a past log → recalibration event created
- [ ] Integration tests for all endpoints
- [ ] `POST /ingestion/seed-demo` — seed all three demo personas

**Definition of done:** CI green. Can log a symptom with a location. Demo seeding works.

---

### Milestone 4 — Correlation Engine (Days 8–11)
**Goal:** The statistical engine runs and produces meaningful results.

- [ ] PercentileNormalizer + unit tests (edge cases: empty history, all-same values)
- [ ] SpearmanCorrelator at each lag + unit tests (verify NaN when insufficient data)
- [ ] PointBiserialCorrelator + unit tests
- [ ] YoudenThresholdFinder + unit tests (verify Youden's J formula, verify sparse fallback)
- [ ] RiskScorer: weighted sum + EWMA + hysteresis + unit tests
- [ ] MismatchDetector: findMismatchDays + unit test against seeded data
- [ ] `POST /correlation/compute`
- [ ] `GET /correlation/results` (per-factor rho, threshold, weight, confidence)
- [ ] `GET /correlation/mismatches`
- [ ] `GET /risk/current` with full factor breakdown
- [ ] `GET /risk/history?from=&to=`
- [ ] Integration test: seed Morgan persona → compute → verify high-sensitivity factors surface
- [ ] Hourly scoring wired into IngestionScheduler (runs after each ingest cycle)

**Definition of done:** CI green. After seeding demo data, `/risk/current` returns a non-trivial score. `/correlation/results` shows Morgan's triggers correctly.

---

### Milestone 5 — Briefing + Reminders (Days 12–13)
**Goal:** Daily briefing generates. Advisory reminders fire. Consent gate works.

- [ ] ConsentRecord + `POST /consent` + `GET /consent`
- [ ] ConsentGuard (service-layer enforcement) + unit tests
- [ ] Medication CRUD with type dropdown validation
- [ ] ReminderRule CRUD with time-window fields
- [ ] ReminderEngine: time-aware evaluation + mandatory disclaimer + unit test (disclaimer always present)
- [ ] `GET /reminders/current`
- [ ] BriefingContext builder + unit test (no raw user text sent to API)
- [ ] BriefingDisclaimerInjector + unit test (disclaimer appended even if Claude omits it)
- [ ] Claude API client + fallback template
- [ ] `GET /briefing/today`
- [ ] Medication usage pattern aggregation
- [ ] Integration test: consent → rule → trigger score → verify reminder + disclaimer
- [ ] Integration test: briefing generated → disclaimer present in response

**Definition of done:** CI green. Reminder fires with disclaimer. Briefing generated with hardcoded disclaimer. Consent gate blocks without consent.

---

### Milestone 6 — Export + Polish (Day 14)
**Goal:** Export works. API is clean. Backend is production-ready.

- [ ] `GET /export?from=&to=` → CSV with symptom log + daily env conditions
- [ ] Open-Meteo attribution in all API responses
- [ ] Error handling: global `@RestControllerAdvice` with consistent error format
- [ ] All Javadocs written (every method — see CLAUDE.md for format)
- [ ] README.md: project overview, setup steps, API tour, known limitations
- [ ] `docker-compose up` works end-to-end locally
- [ ] Deploy to Fly.io: `/actuator/health` and `/swagger-ui.html` accessible at public URL
- [ ] CI green on `main`

**Definition of done:** Publicly accessible Fly.io URL. CI green. README complete. Export works.

---

### Milestone 7 — React Frontend (Days 15–20)
**Goal:** Recruiter can open a URL and interact with Ashoo visually.

- [ ] Vite + React 18 + Tailwind + Recharts + React Query scaffold
- [ ] RiskScoreBadge component (0-100 + color + label)
- [ ] DailyBriefingCard (briefing text + disclaimer)
- [ ] FactorBreakdownList (each factor with percentile bar)
- [ ] RiskOverTimeChart (30-day Recharts LineChart)
- [ ] SymptomLogForm (date/time + severity slider 0-10 + notes + location + meds)
- [ ] SymptomLogHistory table with edit + mismatch highlights
- [ ] ConditionsSearchBox (type a city → get risk + factor breakdown)
- [ ] SavedLocationsList
- [ ] MedicationList + ReminderRuleForm
- [ ] Demo mode toggle + persona switcher in header
- [ ] Demo banner ("viewing demo data for Alex")
- [ ] ExportButton → download CSV
- [ ] Deploy frontend to Vercel with `VITE_API_BASE_URL` pointing to Fly.io backend
- [ ] CI green. Both URLs publicly accessible.

**Definition of done:** Recruiter can open the Vercel URL, switch to a demo persona, see the risk score, read the briefing, query a city, and see the factor breakdown — all without creating an account.

---

### Milestone 8 — Final Portfolio Polish (Days 21+, as time allows)
**Goal:** This is the centerpiece project. Make it look like one.

- [ ] Custom domain (optional: ashoo.dev or similar)
- [ ] OpenGraph meta tags for social sharing preview
- [ ] Loading states and empty states for all components
- [ ] Mobile-responsive layout
- [ ] "How it works" explainer section on the landing page
- [ ] GitHub repository README with screenshots
- [ ] Add to portfolio site (caizhengtech.com)
