# Ashoo - Personal Environmental Health Correlation Engine

> *"What does the air do to **me** specifically — not to the average person?"*

Ashoo is a personal environmental health correlation engine for people with respiratory
allergies and asthma. Apps like AirNow give population-average AQI thresholds. Ashoo
learns **your** personal trigger fingerprint by correlating your self-logged symptom days
against archived environmental data - air quality, pollen, weather, and barometric
pressure — then surfaces a personalized 0–100 **Personal Risk Index (PRI)**, a
plain-English daily briefing, and pattern-based reminders you configured yourself.

The intelligence is honest, transparent statistics computed per-user — **not** machine
learning, and **not** a medical device.

---

## Table of contents

- [What Ashoo is — and is not](#what-ashoo-is--and-is-not)
- [Technology stack](#technology-stack)
- [Architecture](#architecture)
- [Getting started](#getting-started)
- [Frontend](#frontend)
- [Demo system](#demo-system)
- [Personal Risk Index (PRI)](#personal-risk-index-pri)
- [Correlation engine](#correlation-engine)
- [Daily briefing](#daily-briefing)
- [Medications and reminders](#medications-and-reminders)
- [API reference](#api-reference)
- [Data sources](#data-sources)
- [Deploying to Fly.io](#deploying-to-flyio)
- [Configuration reference](#configuration-reference)
- [Known limitations (V1)](#known-limitations-v1)

---

## What Ashoo is — and is not

Ashoo is an **informational wellness tool**. It does **not** diagnose, treat, or prescribe;
it does not tell you to take or skip medication; and it never replaces carrying your
prescribed medication or seeing a doctor. Every reminder is your own pre-configured note
echoed back when conditions match, and every reminder and briefing carries a mandatory,
non-removable disclaimer. These constraints are permanent and enforced in code — they
cannot be toggled off by configuration.

---

## Technology stack

| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Java 21 LTS (virtual threads) | Virtual threads are stable, I/O-bound polling benefits from high concurrency without thread-pool tuning |
| Framework | Spring Boot 3.5 | Industry standard, native virtual-thread support via `spring.threads.virtual.enabled` |
| Database | TimescaleDB (PostgreSQL extension) | Time-series optimized, plain SQL, full Spring Data JDBC compatibility |
| Migrations | Flyway (V1–V8) | Schema versioning, auto-applied at startup |
| HTTP client | Spring RestClient | Synchronous API, virtual-thread-friendly (no WebFlux needed) |
| Scheduling | Spring `@Scheduled` + `SimpleAsyncTaskScheduler` | Virtual-thread-backed polling |
| Testing | JUnit 5 + Testcontainers | Real TimescaleDB in CI, not mocks |
| API docs | springdoc-openapi | Auto-generated Swagger UI at `/swagger-ui.html` |
| AI briefing | Anthropic Claude API (`claude-sonnet-4-6`) | Structured daily briefing with safety guardrails |
| Frontend | React 18 + Vite + Tailwind CSS + React Query | Interactive SPA with live data, persona switcher |
| Containers | Docker + docker-compose | Local dev database in one command |
| Deployment | Fly.io | Always-on or scale-to-zero, Dockerfile-based, ~$2–5/mo |

---

## Architecture

### Backend module structure

```
com.ashoo
├── ingestion          # API clients, scheduler, rate limiting, derived signals
│   ├── openmeteo      # Weather + AQ + pollen client (Open-Meteo)
│   ├── openaq         # Ground-truth AQ readings (OpenAQ v3)
│   └── airnow         # US AQI fallback (EPA AirNow)
├── storage            # TimescaleDB entities + Spring Data JDBC repositories
│   ├── entity         # @Table classes: EnvironmentalSnapshot, SymptomLog, etc.
│   └── repository     # SQL queries and Spring Data repos
├── correlation        # Statistical engine (normalize → correlate → threshold → score)
│   ├── Factor         # Enum of all tracked environmental factors with units
│   ├── CorrelationService  # Spearman + point-biserial + lag analysis
│   ├── RiskScorer     # EWMA smoothing + hysteresis
│   └── RiskScoringService  # Threshold-aware scoring, breadth/intensity blend
├── briefing           # Claude API integration, prompt builder, disclaimer injector
├── reminder           # Consent gate, medications, reminder rules, reminder engine
├── location           # Saved locations, recent searches, geocoding
├── export             # CSV export endpoint
├── api                # REST controllers, DTOs, OpenAPI config, global error handler
└── common             # Config, constants, DemoUsers, AQI calculator, risk levels
```

### Frontend structure

```
frontend/src/
├── api/               # Axios client + named endpoint wrappers
├── components/        # Reusable UI: RiskBadge, FactorBreakdownList, ConditionsReadout,
│                      #   DailyBriefingCard, CurrentReminders, RiskOverTimeChart,
│                      #   DemoExplorer, IntroGate, Layout, ui.jsx (primitives)
├── lib/               # PersonaContext, ToastContext, format utilities
└── pages/             # Dashboard, LogPage, InsightsPage, RemindersPage, PlacesPage
```

### Database schema (Flyway V1–V8)

| Migration | Creates |
|-----------|---------|
| V1 | TimescaleDB extension |
| V2 | `environmental_snapshot` hypertable (hourly env readings) |
| V3 | `symptom_log` (user-logged severity 0–10) |
| V4 | `risk_score_history` hypertable (smoothed PRI over time) |
| V5 | `saved_location`, `recent_search` (location management) |
| V6 | `medication`, `reminder_rule`, `consent_record` (health/reminder system) |
| V7 | `correlation_result`, `briefing_log` (model outputs + briefing cache) |
| V8 | `daily_aggregate` continuous aggregate (kept indefinitely) |

---

## Getting started

### Prerequisites

- **Java 21** (JDK, not JRE — needed to build)
- **Docker Desktop** (for TimescaleDB)
- **Node.js 18+** and **npm** (for the frontend)

> **Windows note:** Docker Desktop uses WSL2 which can be memory-hungry. If you hit
> `0x800705aa` errors, create `C:\Users\<you>\.wslconfig` with `[wsl2]` and
> `memory=3GB` to cap WSL2's RAM usage, then restart Docker Desktop.

### 1. Start the database

```bash
docker-compose up -d
```

This starts a TimescaleDB container on **port 5433** (not 5432, to avoid clashing with
any local PostgreSQL). The data is persisted in a named Docker volume.

### 2. Start the backend

```bash
./gradlew bootRun
```

On first startup, Flyway runs all migrations (V1–V8) to create the schema. The backend
is ready when you see `Started AshooApplication`.

Verify it works:
- **Health:** http://localhost:8080/actuator/health
- **Swagger UI:** http://localhost:8080/swagger-ui.html

### 3. Start the frontend

```bash
cd frontend
npm install       # first time only
npm run dev       # starts Vite dev server on http://localhost:5173
```

The Vite config proxies `/api` requests to `localhost:8080`, so both servers work
together seamlessly. Open http://localhost:5173 in your browser.

### 4. Seed demo data (optional but recommended)

Once both servers are running, click the **Seed** button in the Demo Explorer panel at
the top of the dashboard (or call `POST /api/v1/demo/seed`). This:

1. Fetches 90 days of real environmental data from Open-Meteo (Amsterdam, for pollen)
2. Generates synthetic symptom logs for three demo personas + the default user
3. Computes correlation models and backfills risk trend history for all personas

After seeding, the dashboard, trend chart, factor breakdown, insights, and briefing
are all populated immediately.

### Running the tests

```bash
./gradlew test
```

Tests use Testcontainers to spin up a real TimescaleDB — Docker must be running. On
Windows, the `build.gradle` points Testcontainers at the Docker Desktop named pipe
(`npipe:////./pipe/docker_engine_linux`).

---

## Frontend

### Intro screen

The app opens with a cloud-themed intro overlay. It plays a video background (or falls
back to an animated CSS sky gradient). Clicking anywhere transitions to the dashboard
with a zoom-out effect. The video file goes in `frontend/public/assets/intro.mp4`.

### Pages

| Page | Route | Description |
|------|-------|-------------|
| **Dashboard** | `/` | PRI gauge, medication reminders, daily briefing, risk trend chart, factor breakdown |
| **Log** | `/log` | Log symptoms (severity slider, date chips, notes), view history with range filter |
| **Insights** | `/insights` | Per-factor correlation results, mismatch days, confidence levels |
| **Reminders** | `/reminders` | Consent gate, medication registration, reminder rule creation |
| **Places** | `/places` | Saved locations, live autocomplete search, conditions readout with gauge bars |

### Demo Explorer

A collapsible panel at the top of the dashboard lets you switch between personas (You,
Alex, Jordan, Morgan), re-seed data, and recompute models. Each persona has a different
sensitivity profile and display location. The demo banner is clearly labeled.

### Toast notifications

All mutations (log entry saved, medication added, reminder created, demo seeded) trigger
a brief auto-dismissing toast notification in the bottom-right corner. Implemented via
React Context (`ToastContext.jsx`).

---

## Demo system

### Three personas

| Persona | Sensitivity | Location | Trigger profile | Symptom frequency |
|---------|-------------|----------|----------------|-------------------|
| **Alex** | Low | Los Angeles, CA | PM2.5 > 26 only | Rare, mild (1–3) |
| **Jordan** | Moderate | Atlanta, GA | Pollen > 12 (grass/birch) | Regular, seasonal (3–6) |
| **Morgan** | High | Houston, TX | PM2.5 > 11 OR pollen > 8 OR pressure drop > 3 | Frequent, severe (6–10) |

The **default user ("You")** is seeded with Morgan's trigger profile under "Sharon, MA".
Environmental data is shared across all personas (fetched from Amsterdam for pollen
availability). Symptom logs are persona-specific and marked `data_origin = 'SEEDED_SYNTHETIC'`.

### How seeding works

1. `SeedDemoService.seedAllPersonas()` fetches 90 days of real environmental history from
   Open-Meteo (Amsterdam) and stores it under the default user id.
2. For each persona, it generates synthetic symptom logs by applying their trigger rules
   to the real environmental readings. Each persona gets a different city label.
3. `DemoController` then computes correlation models and backfills 180 days of risk
   history for every persona so trend charts populate immediately.

---

## Personal Risk Index (PRI)

The PRI is a **0–100 personalized score** that answers: "How closely do today's
conditions match MY worst historical days?"

### Risk scale

| Score | Label | Color | Guidance |
|-------|-------|-------|----------|
| 0–20 | Great | Green | Conditions look good for you today |
| 21–40 | Moderate | Yellow | Minor irritants present |
| 41–60 | Elevated | Orange | Conditions match some of your past symptom days |
| 61–80 | High | Red | Conditions closely match your past flare days |
| 81–100 | Severe | Purple | Conditions match your worst recorded days |

### Scoring algorithm

The PRI blends two signals at a **50/50 split**:

1. **Intensity** (weighted mean of threshold-adjusted factor percentiles): For each
   environmental factor, `personalScoreInput(percentile, aboveThreshold)` returns:
   - Above threshold: `60 + 0.4 * percentile` (strong signal regardless of absolute level)
   - Below threshold: `0.35 * percentile` (dampened)
   
   Factors are weighted by `|Spearman rho|` from the correlation model.

2. **Breadth** (count of meaningful active triggers): Only factors that are BOTH above
   the person's threshold AND carry weight >= average model weight count as "meaningful
   active." Breadth = `min(100, count * 18)`.

This ensures that a broadly-sensitive person (many triggers active at once) scores higher
than someone with a single strong trigger, even in the same air.

### Smoothing and hysteresis

The raw blended score is smoothed with **EWMA (lambda = 0.3)** and filtered through
**hysteresis** (alert-on at 70, alert-off at 55) to prevent score flickering near
alert boundaries.

---

## Correlation engine

The engine uses transparent, honest statistics — **not** machine learning.

### Pipeline

1. **Normalize** each factor to 0–100 via personal percentile rank against 180 days of
   history.
2. **Correlate** each factor against symptom severity using Spearman's rho and
   point-biserial correlation, tested at 0/24/48/72-hour lags. The best lag is kept.
3. **Threshold** — learn a personal cut-point per factor via Youden's J statistic
   (requires minimum 10 symptom days). Falls back to the 75th percentile of
   symptom-day readings when data is sparse.
4. **Weight** — factors are weighted by `|Spearman rho|`, normalized to sum to 1.0.
5. **Score** — aggregate with the intensity/breadth blend described above.

### Confidence levels

| Level | Symptom days | Display |
|-------|-------------|---------|
| LOW | < 10 | "You're still early — keep logging for better insights" |
| MEDIUM | 10–29 | Thresholds learned, moderate confidence |
| HIGH | 30+ | Strong personal model |

### Location matching

The engine only uses symptom logs whose city matches the tracked environmental data
location. Logs with no city are included for backward compatibility. Matching is
normalized (lowercased, first comma segment).

### Mismatch days

For transparency, the engine surfaces days where the score was high but you felt fine
(or vice versa). These "mismatch days" are shown in the Insights page.

---

## Daily briefing

A structured paragraph generated by the **Anthropic Claude API** (`claude-sonnet-4-6`).
It is NOT a chatbot — it reads the current risk state and generates a single advisory
summary.

**The briefing reads:**
- Current risk score + factor breakdown
- Today's conditions vs. historical patterns
- Registered medications and reminder rules
- Recent symptom log (last 7 days)

**Hard safety rules (enforced in code):**
- Always ends with "consult your doctor for medical decisions"
- Never says "take [medication]" or specifies dose/frequency
- Never claims to diagnose or predict with certainty
- Uses hedged language: "historically," "similar to," "may"
- If confidence is LOW, says "you're still early — keep logging"

If no `ANTHROPIC_API_KEY` is set, the briefing falls back to a template-based summary.

---

## Medications and reminders

### Consent gate

Before any medication or reminder feature is accessible (frontend or backend), the user
must accept the advisory disclaimer. The `ConsentRecord` is checked on every request to
the medication and reminder endpoints.

### Medications

Users register their own medications with a type (INHALER, ANTIHISTAMINE, EPINEPHRINE,
NASAL_SPRAY, EYE_DROPS, OTHER) and a free-text name. Ashoo never suggests what to
register. Usage is tracked per symptom log entry, and patterns are surfaced ("4 uses this
week vs. 1.2/week average").

### Reminder rules

Each rule has:
- **Threshold:** the PRI score (0–100) at/above which it fires
- **Note:** the user's own words, echoed back verbatim
- **Medication:** optionally linked to a registered medication
- **Time:** when to show the reminder (nothing fires outside this time)

When conditions cross the threshold during the configured time, the user's note is
shown along with the **mandatory disclaimer** (appended at evaluation time, never stored
in the rule, cannot be edited out):

> "This is a suggestion based on your own settings, not medical advice. Always carry
> your prescribed medication. Consult your doctor."

---

## API reference

All endpoints are under `/api/v1`. Full interactive docs at `/swagger-ui.html`.

### Risk

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/risk/current?user=` | Current PRI + factor breakdown |
| `GET` | `/risk/history?from=&to=&user=` | PRI over time (for charting) |

### Correlation

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/correlation/compute?user=` | Recompute the personal model |
| `GET` | `/correlation/results?user=` | Per-factor rho, threshold, weight, confidence |
| `GET` | `/correlation/mismatches?user=` | Days the model disagreed with your logs |

### Symptoms

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/symptoms` | Log a symptom entry (severity 0–10, notes, location) |
| `GET` | `/symptoms?from=&to=` | List symptom entries in range |
| `PUT` | `/symptoms/{id}` | Update a past entry |
| `DELETE` | `/symptoms/{id}` | Delete an entry |

### Locations

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/locations` | Save a location (auto-geocodes from city name) |
| `GET` | `/locations` | List saved locations |
| `PUT` | `/locations/{id}` | Update a saved location |
| `DELETE` | `/locations/{id}` | Soft-delete a location |
| `GET` | `/locations/recent-searches` | Last 10 ad-hoc lookups |
| `GET` | `/locations/suggest?q=` | Type-ahead place suggestions |

### Conditions (ad-hoc lookup)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/conditions?city=Amsterdam` | Live conditions for any city |
| `GET` | `/conditions?lat=52.37&lon=4.90` | Live conditions by coordinates |

### Consent, medications, reminders

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/consent` | Check consent status |
| `POST` | `/consent` | Accept the advisory disclaimer |
| `GET` | `/medications` | List registered medications |
| `POST` | `/medications` | Register a medication |
| `DELETE` | `/medications/{id}` | Remove a medication |
| `GET` | `/medications/usage` | Usage patterns (weekly avg, recent count) |
| `GET` | `/reminder-rules` | List active reminder rules |
| `POST` | `/reminder-rules` | Create a reminder rule |
| `DELETE` | `/reminder-rules/{id}` | Remove a reminder rule |
| `GET` | `/reminders/current` | Reminders firing right now (with disclaimer) |

### Briefing and export

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/briefing/today?user=&demo=` | Plain-English daily briefing |
| `GET` | `/export?from=&to=` | Download symptom log + conditions as CSV |

### Demo

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/demo/profiles` | The three demo personas with descriptions |
| `POST` | `/demo/seed` | Seed all personas with real env + synthetic symptoms |

### Ingestion (manual)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/ingestion/trigger` | Manually trigger an environmental data fetch |

Errors return a consistent JSON shape: `{ timestamp, status, error, message, path }`.

The optional `user` query parameter accepts persona keys (`alex`, `jordan`, `morgan`)
or full ids (`demo-alex`). Unknown values safely resolve to the default user.

---

## Data sources

| Source | Provides | API key? | License |
|--------|----------|----------|---------|
| **Open-Meteo** | Weather, air quality, pollen (Europe only) | No | CC BY 4.0 |
| **OpenAQ v3** | Ground-truth AQ readings | Yes (X-API-Key) | Open |
| **US EPA AirNow** | Official US AQI | Yes | Government open data |

### Environmental factors tracked

**Air quality:** PM2.5, PM10, O3, NO2, SO2, CO (all in micrograms per cubic meter)

**Pollen (Europe only):** Alder, Birch, Grass, Mugwort, Olive, Ragweed (grains per cubic meter)

**Meteorological:** Temperature, humidity, barometric pressure, wind speed, wind gusts

**Derived signals (computed at ingest):** PM2.5 rate of change, 3-hour pressure drop,
24-hour cumulative PM2.5, computed AQI, thunderstorm flag

### Required attribution

> Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)

---

## Deploying to Fly.io

The backend ships as a multi-stage Docker image. The Fly.io config (`fly.toml`) targets
the Boston region, scales to zero when idle, and wakes on request.

```bash
# First time: create the app
fly launch --no-deploy

# Set secrets (never committed to the repo)
fly secrets set DB_HOST=... DB_PORT=5432 DB_NAME=ashoo DB_USER=... DB_PASSWORD=...
fly secrets set ANTHROPIC_API_KEY=...    # optional; omit for template-based briefings
fly secrets set OPENAQ_API_KEY=...       # optional
fly secrets set AIRNOW_API_KEY=...       # optional

# Deploy
fly deploy
```

After deploy:
- Health: `https://<app>.fly.dev/actuator/health`
- Swagger: `https://<app>.fly.dev/swagger-ui.html`

---

## Configuration reference

Environment variables (with defaults from `application.yml`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` | `localhost` | Database host |
| `DB_PORT` | `5433` | Database port (5433 locally to avoid conflicts) |
| `DB_NAME` | `ashoo` | Database name |
| `DB_USER` / `DB_PASSWORD` | `ashoo` / `ashoo` | Database credentials |
| `ANTHROPIC_API_KEY` | *(empty)* | Enables live AI briefings; empty = fallback template |
| `OPENAQ_API_KEY` | *(empty)* | Optional ground-truth AQ source |
| `AIRNOW_API_KEY` | *(empty)* | Optional US AQI source |

### Application config (`application.yml`)

| Key | Value | Purpose |
|-----|-------|---------|
| `spring.threads.virtual.enabled` | `true` | All request/scheduling on virtual threads |
| `ashoo.default-location` | Sharon, MA | V1 development default location |
| `ashoo.demo-location` | Amsterdam | European city for pollen data availability |
| `ashoo.polling.interval-ms` | `3600000` (1hr) | Environmental data polling interval |
| `ashoo.correlation.min-symptom-days` | `10` | Minimum days before Youden's J is used |
| `ashoo.correlation.ewma-lambda` | `0.3` | EWMA smoothing factor |
| `ashoo.correlation.alert-on-threshold` | `70.0` | Hysteresis: score to enter alert state |
| `ashoo.correlation.alert-off-threshold` | `55.0` | Hysteresis: score to leave alert state |

---

## Known limitations (V1)

- **Single user.** Multi-user + auth (Spring Security/JWT) is V2.
- **Pollen is Europe-only.** Open-Meteo's pollen (CAMS) covers Europe, so environmental
  data is fetched from Amsterdam. Sharon, MA gets AQ + weather but no pollen until the
  Google Pollen API is integrated (V2).
- **City/town location granularity.** Block-level precision is V3 scope.
- **Not real-time.** Ingestion polls hourly; the engine is about historical patterns,
  not minute-to-minute forecasting.
- **Correlation is not causation.** Ashoo surfaces personal patterns and always shows
  confidence and mismatch days; it never claims a factor *causes* symptoms.

---

## Safety

Ashoo is not a medical device and provides no medical advice. It does not diagnose,
treat, or prescribe. Always carry your prescribed medication and consult your doctor
for medical decisions.

---

## Attribution

Weather and air quality data by **Open-Meteo.com (ECMWF/CAMS)**.
