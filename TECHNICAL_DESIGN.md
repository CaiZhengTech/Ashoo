# Technical Design Document — Aura Engine v1.0
### Personal Environmental Health Correlation Engine

**Status:** Approved for implementation
**Stack:** Java 21 · Spring Boot 3.5.x · TimescaleDB · Docker · Fly.io
**Author:** Cai Zheng
**Last updated:** June 2026

---

## Table of Contents
1. [System Overview](#1-system-overview)
2. [Architecture Diagram](#2-architecture-diagram)
3. [Database Schema](#3-database-schema)
4. [Module Designs](#4-module-designs)
5. [API Specification](#5-api-specification)
6. [Correlation Engine Design](#6-correlation-engine-design)
7. [Configuration & Environment](#7-configuration--environment)
8. [Testing Strategy](#8-testing-strategy)
9. [Deployment](#9-deployment)
10. [Build Milestones](#10-build-milestones)

---

## 1. System Overview

Aura Engine is a **modular monolith** — one deployable JAR, with clean internal module
boundaries that support future splitting if scale ever justifies it. It runs as a
long-lived backend service that:

1. **Polls** environmental APIs on a schedule (hourly)
2. **Archives** every reading into a TimescaleDB time-series database
3. **Accepts** user-logged symptom entries via REST API
4. **Correlates** symptom logs against environmental history
5. **Scores** current conditions on a personalized 0–100 risk scale
6. **Surfaces** pattern-based, advisory-only reminders

It does NOT have a frontend in V1. All interaction is via a REST API documented at
`/swagger-ui.html`. A React or mobile frontend is V3 scope.

### Non-goals for V1
- Multi-user / authentication
- Forecasting (5-day risk outlook)
- Real-time WebSocket push
- US pollen data (Europe demo location only)
- Any ML framework — honest statistics only

---

## 2. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        Aura Engine JVM                       │
│                                                             │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────────┐  │
│  │  Ingestion   │   │  Correlation │   │   Reminder     │  │
│  │  Scheduler   │   │  Engine      │   │   Engine       │  │
│  │  (virtual    │   │              │   │                │  │
│  │   threads)   │   │  Normalizer  │   │  Medication    │  │
│  │              │   │  Correlator  │   │  Rule Engine   │  │
│  │  Open-Meteo  │   │  Threshold   │   │  Disclaimer    │  │
│  │  OpenAQ      │   │  Scorer      │   │  Guard         │  │
│  │  AirNow      │   │              │   │                │  │
│  └──────┬───────┘   └──────┬───────┘   └───────┬────────┘  │
│         │                  │                   │           │
│         └──────────────────▼───────────────────▼           │
│                     ┌──────────────┐                       │
│                     │   Storage    │                       │
│                     │   Layer      │                       │
│                     │  (Spring     │                       │
│                     │   Data JDBC) │                       │
│                     └──────┬───────┘                       │
│                            │                               │
│  ┌─────────────────────────▼──────────────────────────┐    │
│  │                  REST API Layer                     │    │
│  │         (Spring MVC + springdoc-openapi)            │    │
│  │         /swagger-ui.html auto-generated             │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  Spring Boot Actuator (/actuator/health, /actuator/metrics) │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │     TimescaleDB         │
              │  (Postgres extension)   │
              │                         │
              │  environmental_snapshot │  ← hypertable (time-series)
              │  symptom_log            │  ← hypertable
              │  risk_score_history     │  ← hypertable
              │  medication             │  ← regular table
              │  reminder_rule          │  ← regular table
              │  consent_record         │  ← regular table
              │  correlation_result     │  ← regular table (recomputed)
              └─────────────────────────┘

External APIs (outbound, polled hourly by virtual threads):
  → https://api.open-meteo.com  (weather + AQ + pollen)
  → https://api.openaq.org      (ground-truth air quality)
  → https://www.airnowapi.org   (US AQI fallback)
```

### Why a modular monolith (not microservices)?
At single-user scale, network hops between services add latency with zero benefit.
The modules above communicate via direct Java method calls — zero serialization overhead,
zero network failure surface, trivially debuggable. If Aura ever serves thousands of
concurrent users, the module seams become service boundaries. Until then, the monolith
is the right call.

---

## 3. Database Schema

### Why TimescaleDB?
TimescaleDB is a Postgres extension. This means: plain SQL, standard JDBC driver,
Spring Data works out of the box, and `docker-compose` is one extra line. A TimescaleDB
"hypertable" is a regular Postgres table that the extension automatically partitions by
time — giving fast range queries over millions of timestamped rows. This is exactly
right for hourly sensor readings accumulated over months.

### Flyway migrations (all schema changes go here, in order)

#### V1__create_extensions.sql
```sql
-- Enable the TimescaleDB extension.
-- This must run before any hypertable is created.
-- TimescaleDB adds time-series superpowers to a regular Postgres database.
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;
```

#### V2__create_environmental_snapshot.sql
```sql
-- Stores one row per hourly poll of the environmental APIs.
-- This is the core time-series table — everything the correlation engine
-- reads to understand what the environment was doing on any given day.
CREATE TABLE environmental_snapshot (
    -- The exact UTC moment this reading was recorded.
    -- TimescaleDB will partition the table by this column automatically.
    recorded_at         TIMESTAMPTZ     NOT NULL,

    -- Single-user V1: always "default-user". V2 adds auth and real user IDs.
    user_id             VARCHAR(64)     NOT NULL DEFAULT 'default-user',

    -- Where this reading applies (lat/lon of the monitored location).
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,

    -- Air quality (all in µg/m³ unless noted)
    pm25                DOUBLE PRECISION,   -- Fine particulate matter — strongest asthma evidence
    pm10                DOUBLE PRECISION,   -- Coarse particulate matter
    o3                  DOUBLE PRECISION,   -- Ozone — second-strongest asthma trigger
    no2                 DOUBLE PRECISION,   -- Nitrogen dioxide — traffic-related
    so2                 DOUBLE PRECISION,   -- Sulphur dioxide — combustion-related
    co                  DOUBLE PRECISION,   -- Carbon monoxide (µg/m³)

    -- Pollen (grains/m³, Europe only via Open-Meteo CAMS)
    pollen_alder        DOUBLE PRECISION,
    pollen_birch        DOUBLE PRECISION,
    pollen_grass        DOUBLE PRECISION,   -- Strongest thunderstorm-asthma connection
    pollen_mugwort      DOUBLE PRECISION,
    pollen_olive        DOUBLE PRECISION,
    pollen_ragweed      DOUBLE PRECISION,

    -- Meteorological
    temperature_c       DOUBLE PRECISION,
    humidity_pct        DOUBLE PRECISION,   -- Relative humidity (%) — above 50% = dust mite risk proxy
    pressure_msl_hpa    DOUBLE PRECISION,   -- Barometric pressure at mean sea level
    wind_speed_ms       DOUBLE PRECISION,
    wind_gusts_ms       DOUBLE PRECISION,

    -- Derived signals (computed at ingest time, not from API directly)
    pm25_rate_of_change DOUBLE PRECISION,   -- Delta from prior reading — spike detection
    pressure_drop_3h    DOUBLE PRECISION,   -- Pressure change over 3 hours — storm proxy
    cumulative_pm25_24h DOUBLE PRECISION,   -- Rolling 24h PM2.5 burden
    aqi_computed        INTEGER,            -- Computed from pm25 using EPA 2024 breakpoints
    thunderstorm_flag   BOOLEAN DEFAULT FALSE, -- Heuristic: grass_pollen high + gusts + humidity

    -- Where did this reading come from?
    -- OPEN_METEO, OPENAQ, AIRNOW, SEEDED_SYNTHETIC
    data_source         VARCHAR(32)     NOT NULL,
    data_origin         VARCHAR(32)     NOT NULL DEFAULT 'REAL'  -- REAL or SEEDED_SYNTHETIC
);

-- Convert to a TimescaleDB hypertable, partitioned by recorded_at.
-- This is what gives us fast time-range queries at scale.
-- chunk_time_interval = 7 days means each 7-day window is its own internal partition.
SELECT create_hypertable('environmental_snapshot', 'recorded_at',
    chunk_time_interval => INTERVAL '7 days');

-- Index for querying by user + time range (the most common query pattern)
CREATE INDEX ON environmental_snapshot (user_id, recorded_at DESC);
```

#### V3__create_symptom_log.sql
```sql
-- Stores user-logged symptom days.
-- The user logs entries like "rough breathing today, used inhaler, severity 2/3."
-- This is the LABEL data that the correlation engine trains against.
-- Without this table, there is nothing to correlate against.
CREATE TABLE symptom_log (
    id              BIGSERIAL,
    recorded_at     TIMESTAMPTZ     NOT NULL,
    user_id         VARCHAR(64)     NOT NULL DEFAULT 'default-user',

    -- 0 = no symptoms, 1 = mild, 2 = moderate, 3 = severe
    severity        SMALLINT        NOT NULL CHECK (severity BETWEEN 0 AND 3),

    -- Free text — what the user noticed (e.g., "tight chest, used inhaler")
    notes           TEXT,

    -- Which medications/remedies the user used today (comma-separated medication IDs)
    medications_used TEXT,

    -- REAL = user-entered, SEEDED_SYNTHETIC = generated for demo/testing
    data_origin     VARCHAR(32)     NOT NULL DEFAULT 'REAL'
);

SELECT create_hypertable('symptom_log', 'recorded_at',
    chunk_time_interval => INTERVAL '30 days');

CREATE INDEX ON symptom_log (user_id, recorded_at DESC);
```

#### V4__create_risk_score_history.sql
```sql
-- Stores the computed risk score at each hourly scoring run.
-- This lets the API return a time-series of "how risky was each hour" —
-- useful for showing the user trends over days/weeks.
CREATE TABLE risk_score_history (
    scored_at       TIMESTAMPTZ     NOT NULL,
    user_id         VARCHAR(64)     NOT NULL DEFAULT 'default-user',

    -- The aggregate personalized risk score (0-100)
    risk_score      DOUBLE PRECISION NOT NULL,

    -- The EWMA-smoothed version (reduces noise from hour-to-hour spikes)
    risk_score_smoothed DOUBLE PRECISION,

    -- Whether the score crossed the alert threshold this hour
    alert_triggered BOOLEAN         DEFAULT FALSE,

    -- Snapshot of factor contributions (stored as JSONB for flexibility)
    -- e.g., {"pm25": 72.3, "pollen_grass": 88.1, "humidity": 45.0}
    factor_scores   JSONB
);

SELECT create_hypertable('risk_score_history', 'scored_at',
    chunk_time_interval => INTERVAL '7 days');

CREATE INDEX ON risk_score_history (user_id, scored_at DESC);
```

#### V5__create_user_tables.sql
```sql
-- Stores medications the user pre-registers (their own meds, never app-suggested).
-- The app ONLY echoes these back. It never populates, suggests, or modifies them.
CREATE TABLE medication (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(64)     NOT NULL DEFAULT 'default-user',
    name            VARCHAR(255)    NOT NULL,  -- User-entered name (e.g., "Ventolin inhaler")
    med_type        VARCHAR(32)     NOT NULL,  -- INHALER, ANTIHISTAMINE, EPINEPHRINE, OTHER
    notes           TEXT,                      -- User's own notes
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Stores user-defined reminder rules.
-- "When my risk score exceeds 70, show me this note."
-- The user writes the note. The app only checks the condition and echoes it.
CREATE TABLE reminder_rule (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             VARCHAR(64)     NOT NULL DEFAULT 'default-user',
    risk_score_threshold DOUBLE PRECISION NOT NULL,  -- 0-100 trigger level
    user_note           TEXT            NOT NULL,    -- The note the user wrote themselves
    medication_id       BIGINT          REFERENCES medication(id),
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Stores the user's explicit consent to the advisory-only disclaimer.
-- No reminder feature is accessible until a ConsentRecord exists for the user.
-- This is a safety and legal requirement — do NOT skip it.
CREATE TABLE consent_record (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(64)     NOT NULL DEFAULT 'default-user',
    consented_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    -- The exact disclaimer text the user accepted (store it, since text may evolve)
    disclaimer_text TEXT            NOT NULL,
    ip_address      VARCHAR(64)     -- Optional: for audit trail
);

-- Stores correlation results per factor (recomputed on demand, cached here).
CREATE TABLE correlation_result (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             VARCHAR(64)     NOT NULL DEFAULT 'default-user',
    computed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    factor_name         VARCHAR(64)     NOT NULL,  -- e.g., "pm25", "pollen_grass"
    best_lag_hours      INTEGER,        -- Lag (0/24/48/72h) at which correlation peaks
    spearman_r          DOUBLE PRECISION,  -- Spearman correlation coefficient (-1 to 1)
    point_biserial_r    DOUBLE PRECISION,  -- Point-biserial correlation
    personal_threshold  DOUBLE PRECISION,  -- The learned cut-point (raw units)
    threshold_percentile DOUBLE PRECISION, -- The same threshold expressed as 0-100 percentile
    -- LOW, MEDIUM, HIGH — based on number of labeled days available
    confidence_level    VARCHAR(16),
    symptom_days_used   INTEGER,           -- How many symptom days went into this calculation
    total_days_used     INTEGER            -- Total days of history used
);
```

### Continuous aggregate (TimescaleDB feature)
```sql
-- Pre-computes daily averages so the API can answer
-- "what were average conditions on each day" without scanning every hourly row.
-- TimescaleDB refreshes this automatically as new data arrives.
CREATE MATERIALIZED VIEW daily_environmental_avg
WITH (timescaledb.continuous) AS
SELECT
    user_id,
    time_bucket('1 day', recorded_at) AS day,
    AVG(pm25)               AS avg_pm25,
    AVG(pollen_grass)       AS avg_pollen_grass,
    AVG(humidity_pct)       AS avg_humidity,
    AVG(pressure_msl_hpa)   AS avg_pressure,
    MAX(aqi_computed)       AS max_aqi,
    BOOL_OR(thunderstorm_flag) AS had_thunderstorm_risk
FROM environmental_snapshot
GROUP BY user_id, time_bucket('1 day', recorded_at);
```

---

## 4. Module Designs

### 4.1 Ingestion Module

**Package:** `com.aura.engine.ingestion`

Responsible for polling external APIs on a schedule and writing raw readings to the database.
This is where virtual threads do their work — many outbound HTTP calls happen concurrently
without blocking any OS threads.

```java
// ingestion/scheduler/IngestionScheduler.java

@Component
public class IngestionScheduler {

    /**
     * Triggers the full environmental data ingestion cycle every hour.
     *
     * The @Scheduled annotation tells Spring to call this method automatically
     * on the defined interval. We use fixedDelay (not fixedRate) because we want
     * to wait for the previous ingestion to finish before starting the next one —
     * avoiding the case where a slow API causes overlapping poll cycles.
     *
     * Because spring.threads.virtual.enabled=true is set in application.yml,
     * Spring's SimpleAsyncTaskScheduler runs this on a virtual thread automatically.
     * Virtual threads are extremely lightweight (unlike OS threads), so we can have
     * thousands of them without consuming significant memory.
     */
    @Scheduled(fixedDelay = 3_600_000) // 3,600,000 ms = 1 hour
    public void runIngestionCycle() { ... }
}
```

```java
// ingestion/openmeteo/OpenMeteoClient.java

@Component
public class OpenMeteoClient {

    /**
     * Fetches the current hour's weather, air quality, and pollen readings
     * from the Open-Meteo API for a configured latitude/longitude.
     *
     * Open-Meteo is free (CC BY 4.0), requires no API key, and provides all
     * three data types (weather + AQ + pollen) from a single API endpoint with
     * different parameter groups. We request them together to save API calls.
     *
     * Important: pollen data is ONLY available for European locations. The
     * configured demo location must be in Europe for pollen to be non-null.
     *
     * @param latitude   WGS84 decimal latitude of the location to monitor
     * @param longitude  WGS84 decimal longitude of the location to monitor
     * @return           a raw OpenMeteoResponse DTO with all fetched fields,
     *                   or empty Optionals for fields not available (e.g., pollen
     *                   outside Europe)
     * @throws IngestionException if the API call fails after retry
     */
    public OpenMeteoResponse fetchCurrent(double latitude, double longitude) { ... }

    /**
     * Fetches historical weather and air quality data for a date range,
     * used for seeding the database with real past environmental history.
     *
     * Open-Meteo's Historical Weather API (ERA5 reanalysis) reaches back to 1940
     * for weather and to 2013 for European air quality. This lets us populate
     * months of real environmental history so the correlation engine has data to
     * work with from day one, rather than waiting months for live ingestion.
     *
     * @param latitude   WGS84 decimal latitude
     * @param longitude  WGS84 decimal longitude
     * @param startDate  inclusive start date (ISO-8601, e.g., "2025-01-01")
     * @param endDate    inclusive end date (ISO-8601, e.g., "2025-12-31")
     * @return           list of hourly OpenMeteoResponse objects, one per hour
     * @throws IngestionException if the API call fails or returns an error response
     */
    public List<OpenMeteoResponse> fetchHistorical(
            double latitude, double longitude,
            String startDate, String endDate) { ... }
}
```

```java
// ingestion/ratelimit/RateLimiter.java

@Component
public class ApiRateLimiter {

    /**
     * Acquires a permit to make an API call, blocking the current thread if
     * the rate limit would otherwise be exceeded.
     *
     * OpenAQ allows 60 requests per minute. If we try to poll 100 locations
     * simultaneously with virtual threads, we'd exceed this limit and get HTTP 429
     * (Too Many Requests) responses. This rate limiter holds callers back to
     * respect the limit.
     *
     * We use a Semaphore (a concurrency primitive) rather than Thread.sleep() because
     * a Semaphore correctly coordinates across multiple concurrent virtual threads —
     * each thread waits its turn without wasting CPU cycles.
     *
     * @param apiName  identifier for the API being rate-limited (e.g., "openaq")
     *                 used for logging and metrics
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire(String apiName) throws InterruptedException { ... }
}
```

### 4.2 Storage Module

**Package:** `com.aura.engine.storage`

Thin layer over Spring Data JDBC. Repositories follow Spring conventions — no raw SQL in
service classes, all queries go through repositories.

```java
// storage/repository/EnvironmentalSnapshotRepository.java

public interface EnvironmentalSnapshotRepository
        extends JdbcAggregateTemplate {

    /**
     * Finds all environmental snapshots for a user within a time window,
     * ordered oldest-first. Used by the correlation engine to build
     * the time-series it correlates against symptom logs.
     *
     * The ORDER BY recorded_at ASC is important — the correlation and
     * lag-detection algorithms depend on chronological ordering.
     *
     * @param userId    the user whose snapshots to retrieve
     * @param from      start of the time window (inclusive)
     * @param to        end of the time window (inclusive)
     * @return          list of snapshots in chronological order
     */
    List<EnvironmentalSnapshot> findByUserIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            String userId, OffsetDateTime from, OffsetDateTime to);

    /**
     * Returns the most recent snapshot for a user, used to compute
     * derived signals (rate-of-change, 3-hour pressure drop) that
     * reference the previous reading.
     *
     * @param userId  the user whose latest snapshot to retrieve
     * @return        the most recent snapshot, or empty if none exists yet
     */
    Optional<EnvironmentalSnapshot> findTopByUserIdOrderByRecordedAtDesc(String userId);
}
```

### 4.3 Correlation Module

**Package:** `com.aura.engine.correlation`

The statistical heart of the system. This is where Aura earns its distinction from
generic AQI apps. All computation is honest statistics — transparent, explainable,
and honest about confidence when data is sparse.

```java
// correlation/normalizer/PercentileNormalizer.java

@Component
public class PercentileNormalizer {

    /**
     * Converts a raw environmental measurement to a personalized 0-100 score
     * by computing its percentile rank within the user's own historical distribution.
     *
     * Why percentile rank (not min-max normalization)?
     * Because min-max normalization is sensitive to outliers — one extreme reading
     * would compress all other readings toward zero. Percentile rank is robust:
     * "today's PM2.5 is at the 87th percentile of what this user has seen"
     * is meaningful regardless of outliers, and works across completely different
     * units (µg/m³, grains/m³, hPa) without any conversion.
     *
     * A score of 0 means the current value is the lowest the user has ever recorded.
     * A score of 100 means it's the highest. A score of 80 means it's higher than
     * 80% of the user's historical readings for this factor.
     *
     * @param factorName      name of the environmental factor (e.g., "pm25")
     * @param currentValue    the raw current measurement in its original units
     * @param historicalValues list of all past raw values for this factor (same user)
     * @return                percentile rank as a double between 0.0 and 100.0
     */
    public double normalize(String factorName, double currentValue,
                            List<Double> historicalValues) { ... }
}
```

```java
// correlation/correlator/SpearmanCorrelator.java

@Component
public class SpearmanCorrelator {

    /**
     * Computes the Spearman rank correlation between a time-series of environmental
     * factor values and a time-series of symptom severity scores, tested at multiple
     * time lags.
     *
     * Why Spearman (not Pearson)?
     * Pearson correlation assumes both variables are normally distributed. Environmental
     * data and symptom scores rarely are — they're often skewed (many low-pollution days,
     * few high-pollution days). Spearman correlation works on RANKS rather than raw values,
     * making it robust to non-normal distributions and outliers.
     *
     * Why multiple lags?
     * Asthma and allergy symptoms often lag exposure. Breathing in high-pollen air at 8am
     * might not cause symptoms until the afternoon (lag ~6-8h) or even the next day (lag 24h).
     * We test lags of 0, 24, 48, and 72 hours and report the lag at which correlation peaks.
     *
     * The returned value (rho) ranges from -1.0 to +1.0:
     *   +1.0 = perfect rank agreement (high factor always with high symptoms)
     *    0.0 = no relationship
     *   -1.0 = inverse relationship (rare for pollution/symptom, but possible for temp)
     *
     * IMPORTANT: With ~13 factors tested at 4 lags = 52 statistical tests, we expect
     * 2-3 false positives by chance at p<0.05. Always report the number of tests run
     * alongside results so users understand the multiple-comparisons risk.
     *
     * @param factorValues   time-ordered list of hourly factor readings (raw units)
     * @param symptomScores  time-ordered list of daily severity scores (0-3),
     *                       aligned to the same days as factorValues
     * @param lagHours       the time lag to apply (0, 24, 48, or 72)
     * @return               Spearman's rho at the given lag, or NaN if insufficient data
     */
    public double computeAtLag(List<Double> factorValues,
                               List<Integer> symptomScores,
                               int lagHours) { ... }
}
```

```java
// correlation/threshold/YoudenThresholdFinder.java

@Component
public class YoudenThresholdFinder {

    /**
     * Finds the personal threshold for a factor that best separates the user's
     * symptom days from their non-symptom days, using Youden's J statistic.
     *
     * What is Youden's J?
     * It's a single number that measures how well a threshold separates two groups.
     * J = Sensitivity + Specificity - 1
     * Sensitivity = "of all symptom days, what fraction had the factor above threshold?"
     * Specificity = "of all non-symptom days, what fraction had the factor below threshold?"
     * J ranges from 0 (useless) to 1 (perfect). We pick the threshold that maximizes J.
     *
     * This is the standard medical statistics method for choosing a diagnostic cut-point.
     * We use it here to find YOUR personal cut-point, not a population-average guideline.
     *
     * Fallback (sparse data):
     * If fewer than MIN_SYMPTOM_DAYS (10) symptom days exist, we cannot build a reliable
     * ROC curve. We fall back to returning the 75th percentile of factor values on
     * symptom days as an estimated threshold, labeled with CONFIDENCE_LOW.
     *
     * @param factorValuesOnSymptomDays     list of factor readings on days the user logged symptoms
     * @param factorValuesOnNonSymptomDays  list of factor readings on symptom-free days
     * @return  ThresholdResult with the optimal cut-point, J statistic, and confidence level
     */
    public ThresholdResult findOptimalThreshold(
            List<Double> factorValuesOnSymptomDays,
            List<Double> factorValuesOnNonSymptomDays) { ... }
}
```

```java
// correlation/scorer/RiskScorer.java

@Component
public class RiskScorer {

    /**
     * Computes the current personalized risk score (0-100) by aggregating
     * normalized factor scores, weighted by each factor's learned importance.
     *
     * How weights work:
     * Each factor's weight = |Spearman rho| for that factor (absolute value of its
     * correlation with the user's symptoms). A factor with rho=0.6 gets 3x the weight
     * of a factor with rho=0.2. Factors with no significant correlation get near-zero weight.
     * Weights are normalized so they sum to 1.0.
     *
     * EWMA smoothing:
     * Raw risk scores can jump hour-to-hour as readings fluctuate. We apply an
     * Exponentially Weighted Moving Average with lambda=0.3 to smooth this:
     * smoothed_t = 0.3 * raw_t + 0.7 * smoothed_{t-1}
     * A lower lambda = more smoothing, slower response. 0.3 is a reasonable balance
     * between responsiveness (detecting real events) and noise rejection.
     *
     * Hysteresis:
     * To prevent the alert state from rapidly toggling on/off as the score hovers
     * near a threshold (the "flapping" problem), we use separate on/off thresholds:
     *   - Alert fires when smoothed score crosses ABOVE 70
     *   - Alert clears when smoothed score falls BELOW 55
     * This means once triggered, conditions must meaningfully improve before clearing.
     *
     * @param normalizedFactorScores  map of factor name → percentile score (0-100)
     * @param factorWeights           map of factor name → learned weight (sums to 1.0)
     * @param previousSmoothedScore   the EWMA-smoothed score from the previous hour
     *                                (pass 0.0 if no prior score exists)
     * @param previousAlertState      whether an alert was active in the previous hour
     *                                (needed for hysteresis logic)
     * @return  RiskScoreResult with raw score, smoothed score, and whether alert triggered
     */
    public RiskScoreResult computeScore(
            Map<String, Double> normalizedFactorScores,
            Map<String, Double> factorWeights,
            double previousSmoothedScore,
            boolean previousAlertState) { ... }
}
```

### 4.4 Reminder Module

**Package:** `com.aura.engine.reminder`

Advisory-only. Echoes user's own pre-registered notes when conditions match.
Never makes clinical decisions.

```java
// reminder/ConsentGuard.java

@Component
public class ConsentGuard {

    /**
     * Verifies that the user has explicitly accepted the advisory disclaimer
     * before any reminder feature is accessed.
     *
     * This is a safety and regulatory requirement. The FDA's General Wellness
     * guidance requires that wellness apps make clear they are NOT medical devices.
     * We enforce this by requiring an explicit, timestamped consent record before
     * any medication-related feature is accessible.
     *
     * Without this guard, a user could receive a reminder about their medication
     * before they've agreed that this is advisory-only — creating potential liability
     * and, more importantly, a risk of them trusting the app too much.
     *
     * @param userId  the user whose consent to verify
     * @throws ConsentRequiredException if no consent record exists for this user,
     *                                  with a message instructing them to accept
     *                                  the disclaimer at POST /api/v1/consent
     */
    public void requireConsent(String userId) { ... }
}
```

```java
// reminder/ReminderEngine.java

@Component
public class ReminderEngine {

    private static final String MANDATORY_DISCLAIMER =
        "⚠️ This is a suggestion based on your own settings, not medical advice. " +
        "Always carry your prescribed medication. Consult your doctor for health decisions.";

    /**
     * Evaluates the user's active reminder rules against the current risk score
     * and returns any reminders whose conditions are met, each wrapped with the
     * mandatory advisory disclaimer.
     *
     * What this method does NOT do (safety boundaries):
     * - It does NOT suggest which medication to take
     * - It does NOT determine dose, frequency, or timing
     * - It does NOT override or modify what the user wrote in their rule
     * - It does NOT create any reminder content — it only echoes what the user wrote
     *
     * The disclaimer is appended unconditionally to every reminder, regardless of
     * the user's note content. It cannot be disabled or removed.
     *
     * @param userId        the user whose reminder rules to evaluate
     * @param currentScore  the current smoothed risk score (0-100)
     * @return              list of triggered ReminderResult objects, each containing
     *                      the user's own note and the mandatory disclaimer;
     *                      empty list if no rules are triggered
     * @throws ConsentRequiredException if the user has not accepted the disclaimer
     */
    public List<ReminderResult> evaluateReminders(String userId, double currentScore) { ... }
}
```

---

## 5. API Specification

Base path: `/api/v1`
All responses: `application/json`
Auto-documented at: `GET /swagger-ui.html`

### Ingestion & snapshot endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/ingestion/trigger` | Manually trigger an ingestion cycle (for testing) |
| `POST` | `/ingestion/seed-history` | Backfill historical data from Open-Meteo for a date range |
| `GET`  | `/snapshots/latest` | Most recent environmental snapshot |
| `GET`  | `/snapshots?from=&to=` | Snapshots within a time range |
| `GET`  | `/snapshots/daily-averages?from=&to=` | Daily averages via continuous aggregate |

### Symptom log endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/symptoms` | Log a symptom entry (severity 0-3, optional notes) |
| `GET`  | `/symptoms?from=&to=` | Retrieve symptom log for a date range |
| `DELETE` | `/symptoms/{id}` | Delete a mistaken entry |

### Correlation & risk endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/correlation/compute` | Recompute correlations from all available history |
| `GET`  | `/correlation/results` | Current correlation results per factor |
| `GET`  | `/risk/current` | Current risk score with factor breakdown |
| `GET`  | `/risk/history?from=&to=` | Historical risk scores over time |

### Reminder & medication endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/consent` | Accept advisory disclaimer (required before any reminder access) |
| `GET`  | `/consent` | Check whether consent has been given |
| `POST` | `/medications` | Register a medication (user's own, no suggestions) |
| `GET`  | `/medications` | List registered medications |
| `DELETE` | `/medications/{id}` | Remove a medication |
| `POST` | `/reminder-rules` | Create a reminder rule (threshold + user's own note) |
| `GET`  | `/reminder-rules` | List active reminder rules |
| `GET`  | `/reminders/current` | Check if any reminder rules fire at current risk score |

### Example response: `GET /risk/current`

```json
{
  "userId": "default-user",
  "scoredAt": "2025-09-15T14:00:00Z",
  "riskScore": 74.2,
  "riskScoreSmoothed": 68.9,
  "alertTriggered": false,
  "confidenceLevel": "MEDIUM",
  "dataNote": "Based on 47 days of history including 12 symptom days.",
  "factorBreakdown": {
    "pm25": { "rawValue": 18.3, "unit": "µg/m³", "percentileScore": 71.0, "weight": 0.28 },
    "pollen_grass": { "rawValue": 42.1, "unit": "grains/m³", "percentileScore": 83.0, "weight": 0.35 },
    "humidity_pct": { "rawValue": 67.0, "unit": "%", "percentileScore": 72.0, "weight": 0.21 },
    "pressure_drop_3h": { "rawValue": -3.2, "unit": "hPa", "percentileScore": 55.0, "weight": 0.16 }
  },
  "disclaimer": "Weather data by Open-Meteo.com (ECMWF/CAMS). This score is based on your personal history and is not medical advice.",
  "attribution": "Weather and air quality data by Open-Meteo.com"
}
```

---

## 6. Correlation Engine Design

### The full computation pipeline

```
Every 1 hour:
  1. Ingest new environmental snapshot → store in environmental_snapshot
  2. Compute derived signals (rate-of-change, pressure drop, thunderstorm flag)
  3. Run PercentileNormalizer → normalize each factor to 0-100 (personal)
  4. Run RiskScorer → weighted aggregate + EWMA + hysteresis → risk_score_history
  5. Run ReminderEngine → evaluate active rules → surface any triggered reminders

On demand (POST /correlation/compute, or auto-triggered after every 5th symptom log):
  1. Fetch all environmental_snapshot rows for user (chronological)
  2. Fetch all symptom_log rows for user (align by day)
  3. For each factor × each lag (0/24/48/72h):
       a. Run SpearmanCorrelator → rho
       b. Run PointBiserialCorrelator → rpb (symptom binary vs factor continuous)
  4. For each factor:
       a. Split into symptom-day values vs non-symptom-day values
       b. Run YoudenThresholdFinder → personal cut-point + confidence
       c. Save to correlation_result table
  5. Recompute factor weights = |rho| normalized to sum to 1.0
  6. Update RiskScorer's weight map for next hourly run
```

### Confidence levels

| Label | Condition |
|-------|-----------|
| `LOW` | < 10 symptom days available — thresholds are estimates, show "keep logging" message |
| `MEDIUM` | 10–29 symptom days — thresholds meaningful but uncertain |
| `HIGH` | 30+ symptom days with reasonable non-symptom days — thresholds statistically robust |

### Synthetic history seeding

For demo purposes, a seed script:
1. Backfills 90 days of REAL historical weather + AQ from Open-Meteo
2. Generates SYNTHETIC symptom logs correlated with the real data:
   - On days where real PM2.5 > 20 µg/m³ AND real pollen_grass > 30: 70% chance of severity 2-3
   - On days where real humidity > 65% AND real pressure dropped > 5 hPa: 50% chance of severity 1-2
   - Otherwise: 10% chance of severity 1
3. All synthetic rows have `data_origin = 'SEEDED_SYNTHETIC'`
4. The API always labels synthetic data clearly in responses

---

## 7. Configuration & Environment

### application.yml
```yaml
spring:
  application:
    name: aura-engine

  # Enable virtual threads — the single most important modern Java feature in this app.
  # With this enabled, Spring runs scheduled tasks, async methods, and HTTP request
  # handling on virtual threads instead of OS threads. For an I/O-bound polling app
  # (waiting for API responses), virtual threads let us handle many concurrent
  # outbound calls without blocking expensive OS threads.
  threads:
    virtual:
      enabled: true

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:aura}
    username: ${DB_USER:aura}
    password: ${DB_PASSWORD:aura}

  flyway:
    enabled: true
    locations: classpath:db/migration

aura:
  location:
    # Demo location: Amsterdam (European city — required for Open-Meteo pollen data)
    latitude: 52.3676
    longitude: 4.9041
    name: "Amsterdam, Netherlands"

  polling:
    interval-ms: 3600000  # 1 hour

  openaq:
    api-key: ${OPENAQ_API_KEY}
    base-url: https://api.openaq.org
    rate-limit-per-minute: 55  # 60 is the limit; leave 5 as buffer

  correlation:
    min-symptom-days: 10        # Minimum before computing Youden thresholds
    lag-windows: [0, 24, 48, 72]  # Hours to test
    ewma-lambda: 0.3
    alert-on-threshold: 70.0
    alert-off-threshold: 55.0

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

### Required environment variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_HOST` | Postgres/TimescaleDB host | `localhost` |
| `DB_PORT` | Postgres port | `5432` |
| `DB_NAME` | Database name | `aura` |
| `DB_USER` | Database user | `aura` |
| `DB_PASSWORD` | Database password | `changeme` |
| `OPENAQ_API_KEY` | OpenAQ v3 API key (free from explore.openaq.org) | `your-key-here` |

### docker-compose.yml
```yaml
version: '3.9'

services:
  timescaledb:
    image: timescale/timescaledb:latest-pg16
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: aura
      POSTGRES_USER: aura
      POSTGRES_PASSWORD: aura
    volumes:
      - timescale_data:/var/lib/postgresql/data

  aura-engine:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: timescaledb
      DB_PORT: 5432
      DB_NAME: aura
      DB_USER: aura
      DB_PASSWORD: aura
      OPENAQ_API_KEY: ${OPENAQ_API_KEY}
    depends_on:
      - timescaledb

volumes:
  timescale_data:
```

---

## 8. Testing Strategy

### Why Testcontainers (not H2)?
H2 is an in-memory database often used in testing, but it is NOT Postgres-compatible.
TimescaleDB-specific features (hypertables, `create_hypertable()`, continuous aggregates)
simply do not exist in H2 — tests against H2 would pass while the real DB fails.
Testcontainers spins up a real TimescaleDB Docker container for each test run,
giving you true production fidelity in CI.

### Test layers

```
Unit tests (no DB, no network):
  → PercentileNormalizerTest      — verify normalization math
  → SpearmanCorrelatorTest        — verify correlation at each lag
  → YoudenThresholdFinderTest     — verify threshold selection logic
  → AqiCalculatorTest             — verify EPA 2024 breakpoints exactly
  → RiskScorerTest                — verify EWMA + hysteresis behavior
  → ReminderEngineTest            — verify disclaimer always appended

Integration tests (Testcontainers — real TimescaleDB):
  → IngestionIntegrationTest      — ingest a recorded Open-Meteo response,
                                    verify it appears in the DB correctly
  → CorrelationIntegrationTest    — seed synthetic history, run correlation,
                                    verify expected factors surface at known thresholds
  → ReminderIntegrationTest       — create consent + rule, trigger score, verify reminder fires
  → ApiIntegrationTest            — full HTTP round-trip against running app
```

### GitHub Actions CI (`.github/workflows/ci.yml`)
```yaml
name: CI
on: [push, pull_request]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Build and test (Testcontainers pulls TimescaleDB)
        run: ./gradlew test
      - name: Build JAR
        run: ./gradlew bootJar
```

---

## 9. Deployment

### Dockerfile
```dockerfile
# Build stage — compile the app
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Run stage — lean runtime image (no JDK tools, just the JRE)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Run the JAR. The JVM flags enable virtual-thread-related optimizations.
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Fly.io deployment
```bash
# One-time setup
fly auth login
fly launch --name aura-engine --region iad  # Detects Dockerfile automatically

# Set environment variables (secrets, never committed to git)
fly secrets set OPENAQ_API_KEY=your-key-here
fly secrets set DB_PASSWORD=your-db-password

# TimescaleDB on Fly: attach a Postgres app and enable the extension
fly postgres create --name aura-db
fly postgres attach aura-db
# Then in your first Flyway migration: CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

# Deploy
fly deploy
```

---

## 10. Build Milestones

Follow these in order. Do NOT start a milestone before the previous one passes CI.

### Milestone 1 — Foundation (Days 1–2)
**Goal:** Spring Boot app runs, connects to TimescaleDB, Flyway runs all migrations.

- [ ] Initialize project with Spring Initializr (Spring Web, Spring Data JDBC, Flyway, Actuator, springdoc-openapi, Testcontainers)
- [ ] Write Flyway migrations V1 through V5
- [ ] Verify `GET /actuator/health` returns `{"status":"UP"}`
- [ ] Verify `GET /swagger-ui.html` renders
- [ ] Write and pass a Testcontainers integration test that runs Flyway and counts tables
- [ ] Set up GitHub Actions CI — must pass on push

**Definition of done:** CI is green. TimescaleDB has the schema. Swagger UI is accessible.

---

### Milestone 2 — Ingestion (Days 3–5)
**Goal:** Hourly poller runs, reads from Open-Meteo, writes to the DB, respects rate limits.

- [ ] Implement `OpenMeteoClient` (weather + AQ + pollen in one call)
- [ ] Implement derived signal computation (rate-of-change, pressure drop, thunderstorm flag)
- [ ] Implement `ApiRateLimiter` with Semaphore
- [ ] Implement `IngestionScheduler` with `@Scheduled` and virtual threads
- [ ] Implement `POST /ingestion/trigger` (manual trigger for testing)
- [ ] Implement `GET /snapshots/latest`
- [ ] Write integration test: trigger ingestion against recorded (mocked) API response, verify DB row
- [ ] Implement `POST /ingestion/seed-history` with Open-Meteo historical API backfill

**Definition of done:** CI green. A manual trigger call creates a row in `environmental_snapshot`. Historical backfill can populate 90 days.

---

### Milestone 3 — Symptom logging (Days 5–6)
**Goal:** User can log symptom days via the REST API.

- [ ] Implement `POST /symptoms`, `GET /symptoms`, `DELETE /symptoms/{id}`
- [ ] Implement `SymptomLog` entity and repository
- [ ] Validate severity is 0–3
- [ ] Write integration tests for all three endpoints

**Definition of done:** CI green. Symptom log endpoints return correct data.

---

### Milestone 4 — Correlation engine (Days 7–10)
**Goal:** The statistical engine runs, produces results, confidence is labeled correctly.

- [ ] Implement `PercentileNormalizer` + unit tests
- [ ] Implement `SpearmanCorrelator` at multiple lags + unit tests
- [ ] Implement `YoudenThresholdFinder` with sparse-data fallback + unit tests
- [ ] Implement `RiskScorer` with EWMA + hysteresis + unit tests
- [ ] Wire into `POST /correlation/compute` and `GET /correlation/results`
- [ ] Implement `GET /risk/current` with factor breakdown
- [ ] Implement `GET /risk/history`
- [ ] Seed synthetic symptom history correlated with real Open-Meteo historical data
- [ ] Write correlation integration test: verify known seeded patterns are recovered

**Definition of done:** CI green. With seeded history, `/risk/current` returns a non-trivial score. `/correlation/results` surfaces the seeded factors as highest-correlation.

---

### Milestone 5 — Reminder system (Days 11–12)
**Goal:** Advisory reminder system works with consent gate and mandatory disclaimer.

- [ ] Implement consent endpoint + `ConsentRecord` entity
- [ ] Implement `ConsentGuard` (throws if no consent)
- [ ] Implement medication CRUD endpoints
- [ ] Implement reminder rule CRUD endpoints
- [ ] Implement `ReminderEngine` with mandatory disclaimer — unit test that disclaimer cannot be absent
- [ ] Implement `GET /reminders/current`
- [ ] Write integration test: create consent, rule, trigger score — verify reminder fires with disclaimer

**Definition of done:** CI green. Reminder endpoint returns triggered rules with disclaimer. Consent gate blocks access when no consent exists.

---

### Milestone 6 — Polish, deploy, document (Days 13–14)
**Goal:** Deployed to Fly.io, accessible, README explains the project.

- [ ] Write `Dockerfile` and test local `docker-compose up`
- [ ] Add Open-Meteo attribution to all API responses
- [ ] Write `README.md` with: project purpose, setup instructions, API overview, known limitations (Europe-only pollen, advisory-only reminders, demo uses synthetic symptom data)
- [ ] Deploy to Fly.io — verify `/actuator/health` and `/swagger-ui.html` accessible at public URL
- [ ] Push final state — CI green on `main`

**Definition of done:** Publicly accessible Fly.io URL. CI green. README is comprehensive. Swagger UI documents all endpoints.
