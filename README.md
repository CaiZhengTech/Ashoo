# Ashoo — Personal Environmental Health Correlation Engine

> *"What does the air do to **me** specifically — not to the average person?"*

Ashoo is a personal environmental health correlation engine for people with respiratory
allergies and asthma. Instead of population-average AQI thresholds, it learns **your**
personal trigger fingerprint by correlating your self-logged symptom days against archived
environmental data — air quality, pollen, weather, and barometric pressure — then surfaces
a personalized 0–100 **Personal Risk Index (PRI)**, a plain-English daily briefing, and
pattern-based reminders you configured yourself.

The intelligence is honest, transparent statistics computed per-user — **not** machine
learning, and **not** a medical device.

---

## What Ashoo is — and is not

Ashoo is an **informational wellness tool**. It does **not** diagnose, treat, or prescribe;
it does not tell you to take or skip medication; and it never replaces carrying your
prescribed medication or seeing a doctor. Every reminder is your own pre-configured note
echoed back when conditions match, and every reminder and briefing carries a mandatory,
non-removable disclaimer. These constraints are permanent and enforced in code.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (virtual threads) |
| Framework | Spring Boot 3.5 |
| Database | TimescaleDB (PostgreSQL + time-series) |
| Migrations | Flyway (V1–V8) |
| HTTP client | Spring RestClient |
| Testing | JUnit 5 + Testcontainers (real TimescaleDB) |
| API docs | springdoc-openapi (Swagger UI) |
| AI briefing | Anthropic Claude API (with offline fallback) |
| Containers | Docker + docker-compose |
| Deployment | Fly.io |

---

## Architecture

```
com.ashoo
├── ingestion     # Open-Meteo clients, scheduler, rate limiting, derived signals
├── storage       # TimescaleDB entities + repositories
├── correlation   # Statistical engine: normalize → correlate → threshold → score
├── briefing      # Claude API integration, context builder, disclaimer injector
├── reminder      # Consent gate, medications, reminder rules, reminder engine
├── export        # CSV export
├── api           # REST controllers, DTOs, global error handler
└── common        # Config, constants, AQI calculator, risk levels
```

### How the correlation engine works (honest statistics, not ML)

1. **Normalize** each factor to a 0–100 personal percentile rank.
2. **Correlate** each factor against symptom severity using Spearman's rho and
   point-biserial correlation, tested at 0/24/48/72-hour lags.
3. **Threshold** — learn a personal cut-point per factor via Youden's J, with a
   75th-percentile fallback when symptom days are sparse.
4. **Score** — weight factors by |Spearman rho|, aggregate, smooth with EWMA (λ=0.3),
   and apply hysteresis (alert on at 70, off at 55).
5. **Confidence** (LOW/MEDIUM/HIGH by symptom-day count) and **mismatch days**
   ("score was 85 but you felt fine") are always surfaced for transparency.

---

## Running locally

**Prerequisites:** Java 21, Docker Desktop.

```bash
# 1. Start TimescaleDB (maps container 5432 → host 5433 to avoid clashing with a
#    local Postgres on 5432).
docker-compose up -d

# 2. Run the app (Flyway applies migrations V1–V8 on startup).
./gradlew bootRun

# 3. Open the API docs.
#    Swagger UI:  http://localhost:8080/swagger-ui.html
#    Health:      http://localhost:8080/actuator/health
```

### Running the frontend

The React UI lives in [`frontend/`](frontend/README.md). With the backend running on
`:8080`:

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 (proxies /api → :8080)
```

The browser cross-origin call is allowed by `com.ashoo.common.WebCorsConfig`; add a
production frontend origin via the `ashoo.cors.allowed-origins` property. See
[`frontend/README.md`](frontend/README.md) for the full page tour and deploy steps.

### Running the tests

The test suite uses Testcontainers to spin up a real TimescaleDB — Docker must be running.

```bash
./gradlew test
```

> **Windows note:** the test task points Testcontainers at the Docker Desktop named pipe
> (`npipe:////./pipe/docker_engine_linux`) and pins the Docker API version. See `build.gradle`.

---

## API tour

All endpoints are under `/api/v1`. Highlights:

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/conditions?city=Amsterdam` | Live conditions for any city (geocoded on demand) |
| `GET`  | `/snapshots/latest` | Latest stored environmental snapshot |
| `POST` | `/symptoms` | Log a symptom (severity 0–10) |
| `GET`  | `/symptoms?from=&to=` | List symptom entries |
| `POST` | `/locations` | Save a location (auto-geocodes from city name) |
| `POST` | `/correlation/compute` | Recompute the personal model |
| `GET`  | `/correlation/results` | Per-factor rho, threshold, weight, confidence |
| `GET`  | `/correlation/mismatches` | Days the model disagreed with your logs |
| `GET`  | `/risk/current` | Current Personal Risk Index + factor breakdown |
| `GET`  | `/risk/history?from=&to=` | PRI over time (for charting) |
| `POST` | `/consent` | Accept the advisory disclaimer (unlocks reminders/meds) |
| `POST` | `/medications` | Register your own medication |
| `POST` | `/reminder-rules` | Create a reminder rule |
| `GET`  | `/reminders/current` | Reminders firing now (each with the disclaimer) |
| `GET`  | `/briefing/today` | Plain-English daily briefing (ends with disclaimer) |
| `GET`  | `/export?from=&to=` | Download symptom log + daily conditions as CSV |
| `GET`  | `/demo/profiles` | The three demo personas (Alex/Jordan/Morgan) |
| `POST` | `/demo/seed` | Seed demo data (real Amsterdam env + synthetic symptoms) |

Errors return a consistent JSON shape: `{ timestamp, status, error, message, path }`.

---

## Deploying to Fly.io

The backend ships as a Dockerfile-based Fly.io app. It needs a PostgreSQL/TimescaleDB
instance; the connection is provided via secrets, never committed.

```bash
fly launch --no-deploy            # create the app from fly.toml (first time only)

# Provide the database connection and any API keys as secrets:
fly secrets set DB_HOST=... DB_PORT=5432 DB_NAME=ashoo DB_USER=... DB_PASSWORD=...
fly secrets set ANTHROPIC_API_KEY=...   # optional; omit to use the offline briefing fallback

fly deploy                         # build the Dockerfile and ship
```

After deploy, `/(actuator/health)` and `/swagger-ui.html` are reachable at the public URL.
The app scales to zero when idle and wakes on request to keep costs low.

### Configuration

Environment variables (with defaults from `application.yml`):

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `ashoo` | Database connection |
| `DB_USER` / `DB_PASSWORD` | `ashoo` / `ashoo` | Database credentials |
| `ANTHROPIC_API_KEY` | *(empty)* | Enables live AI briefings; empty → fallback template |
| `OPENAQ_API_KEY` / `AIRNOW_API_KEY` | *(empty)* | Optional ground-truth AQ sources |

---

## Known limitations (V1)

- **Single user.** Multi-user + auth (Spring Security/JWT) is V2.
- **Pollen is Europe-only.** Open-Meteo's pollen (CAMS) covers Europe, so the demo uses
  Amsterdam. Sharon, MA gets air quality + weather but no pollen until the Google Pollen
  API is added in V2.
- **City/town location granularity.** Block-level precision is out of scope (V3).
- **Not real-time.** Ingestion polls hourly; the engine is about historical patterns,
  not minute-to-minute forecasting.
- **Correlation ≠ causation.** Ashoo surfaces personal patterns and always shows
  confidence and mismatch days; it never claims a factor *causes* symptoms.

---

## Attribution

Weather and air quality data by **Open-Meteo.com (ECMWF/CAMS)**.

---

## Safety

Ashoo is not a medical device and provides no medical advice. Always carry your prescribed
medication and consult your doctor for medical decisions.
