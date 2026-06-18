# CLAUDE.md — Ashoo
### Personal Environmental Health Correlation Engine

> **Read this file at the start of every session, every time, without exception.**
> This is the single source of truth for every decision made about this project.
> Never invent context. If something is not here, ask before assuming.
> The project is called **Ashoo** — "allergy shoo." That's the name everywhere.

---

## What Ashoo is

Ashoo is a **personal environmental health correlation engine** for people with
respiratory allergies and asthma. It answers one question that no existing app answers:

> *"What does the air do to ME specifically — not to the average person?"*

Apps like AirNow give you population-average AQI thresholds. Ashoo learns YOUR personal
trigger fingerprint by correlating your self-logged symptom days against archived
environmental data — air quality, pollen, weather, and barometric pressure — then surfaces
a personalized risk score, a plain-English daily briefing, and pattern-based reminders.

### The thesis
People with respiratory allergies face two bad options today:
1. Take antihistamines or use an inhaler every day "just in case" — over-reliance on
   medication (first-generation antihistamines like Benadryl carry documented long-term
   cognitive risks first identified in 2015 after 83 years on the market).
2. React only after symptoms appear, with no advance awareness.

Ashoo offers a third path: **informed awareness** — know when conditions historically
precede YOUR bad days, so you carry your inhaler when it genuinely matters.

### The gap Ashoo fills
Existing tools (AirNow, IQAir, PurpleAir) tell you what the air is doing.
Ashoo tells you what the air does **to you specifically**, based on your own logged history.
No other free consumer tool does this. That gap is documented in peer-reviewed literature
(e.g., the Barcelona 2025 wearable-asthma feasibility study: even rigorous small-n studies
find no consistent short-term exposure–symptom link, because the relationship is individual).

---

## What Ashoo is NOT (non-negotiable)

- It is NOT a medical device.
- It does NOT diagnose, treat, or prescribe.
- It does NOT tell the user to take or skip medication.
- It does NOT replace carrying prescribed medication or seeing a doctor.
- Every reminder is the user's OWN pre-configured note echoed back when conditions match.
- The daily briefing is advisory only, always closes with "consult your doctor."
- These constraints are permanent. They are not relaxed in any version.

---

## Target user

**Primary:** Generic user with respiratory allergies or asthma.
**V1 development default:** Cai Zheng — Sharon, MA 02067 — hardcoded as the default
starting location during development. All API calls, seeded data, and default config
use Sharon, MA until a user sets their own location.

**Three demo personas** (seeded at startup for recruiter/demo mode):
- **Alex** — low sensitivity, high tolerance, mild occasional symptoms
- **Jordan** — moderate sensitivity, moderate tolerance, regular seasonal patterns
- **Morgan** — high sensitivity, low tolerance, frequent severe episodes

Demo mode is clearly labeled throughout the UI and API. Synthetic data is marked
`data_origin = 'SEEDED_SYNTHETIC'` in the database. Real user data is `'REAL'`.

---

## Risk scale (locked — do not change without updating this file)

Ashoo uses a **0–100 Personal Risk Index (PRI)**, NOT the EPA AQI scale.
The PRI is personalized — 80/100 means "conditions match YOUR worst days," not a
population average. This distinction must be communicated clearly to users.

| Score | Label | Color | Action guidance |
|-------|-------|-------|----------------|
| 0–20  | Great    | 🟢 Green  | Conditions look good for you today |
| 21–40 | Moderate | 🟡 Yellow | Minor irritants present — most people fine |
| 41–60 | Elevated | 🟠 Orange | Conditions match some of your past symptom days |
| 61–80 | High     | 🔴 Red    | Conditions closely match your past flare days — have meds handy |
| 81–100| Severe   | 🟣 Purple | Conditions match your worst recorded days — limit outdoor exposure |

---

## Data sources (locked)

| Source | Provides | Free? | Key? | Storage OK? |
|--------|----------|-------|------|-------------|
| Open-Meteo | Weather, AQ, pollen (Europe) | ✅ | ❌ | ✅ CC BY 4.0 |
| OpenAQ v3 | Ground-truth AQ readings | ✅ | ✅ X-API-Key | ✅ Open license |
| US EPA AirNow | Official US AQI | ✅ | ✅ | ✅ Gov open data |

**Critical pollen constraint:** Open-Meteo pollen is Europe-only (CAMS).
V1 demo location uses Amsterdam for pollen availability.
Sharon, MA gets AQ + weather but no pollen from Open-Meteo.
US pollen → Google Pollen API (V2, requires billing key).

**Do NOT use:** WAQI/aqicn (forbids archiving), PurpleAir (now paid),
BreezoMeter (absorbed by Google).

**Required attribution on all API responses and UI:**
> "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)"

---

## Factor set (all stored per hourly snapshot)

**Air quality:** pm25, pm10, o3, no2, so2, co (all µg/m³)
**Pollen (Europe):** pollen_alder, pollen_birch, pollen_grass, pollen_mugwort,
pollen_olive, pollen_ragweed (grains/m³)
**Meteorological:** temperature_c, humidity_pct, pressure_msl_hpa,
wind_speed_ms, wind_gusts_ms
**Derived (computed at ingest):** pm25_rate_of_change, pressure_drop_3h_hpa,
cumulative_pm25_24h, aqi_computed, thunderstorm_flag (boolean heuristic)

**EPA AQI PM2.5 breakpoints — post May 6 2024 (verified, locked):**
Good 0–9.0, Moderate 9.1–35.4, USG 35.5–55.4, Unhealthy 55.5–125.4,
Very Unhealthy 125.5–225.4, Hazardous 225.5–325.4 (all µg/m³)

---

## Symptom logging spec

Every log entry captures:
- `logged_at` — date and time (user can edit retroactively)
- `severity` — integer 0–10 (0 = no symptoms, 10 = severe episode)
- `notes` — free text (quick, no forced taxonomy)
- `location_id` — FK to saved_location (city/town or county level)
- `medications_used` — array of medication IDs from the user's registered list
- `data_origin` — REAL or SEEDED_SYNTHETIC

Users can edit any past log entry at any time. When a log entry is edited
(including adding a previously-omitted location), the correlation engine
recomputes all weights and thresholds automatically. This recalibration is
logged in `recalibration_event` for transparency.

---

## Location model

**Granularity:** City/town level (primary), county level (broad view option).
**Precision rationale:** Pollen and regional AQI don't change block-by-block.
City/town is sufficient for allergy triggers. Coordinate-level is V3 scope.

**Saved locations:** User pre-registers a named list (Home, Work, etc.).
The engine pre-fetches environmental data for all saved locations hourly.

**Recent searches:** Ad-hoc location queries are stored in a `recent_search`
table (last 10, rolling). Not permanent saves — just quick re-access.

**Interactive query:** Any endpoint accepting a location also accepts
lat/lon or city name for on-demand lookups by unauthenticated users (demo feature).

**Future (not in scope):** Geographic risk heat map of surrounding towns,
route optimization via Google/Apple Maps API, automatic GPS tracking via mobile.

---

## Correlation engine rules

This is NOT machine learning. Never describe it as AI or ML to users.
It is transparent, honest statistics. These rules are permanent:

1. Normalize each factor to 0–100 via personal percentile rank
2. Compute Spearman + point-biserial correlation at lags 0/24/48/72h
3. Learn personal thresholds via Youden's J (min 10 symptom days)
   — fall back to 75th percentile of symptom-day readings when sparse
4. Weight factors by |Spearman rho|, normalized to sum to 1.0
5. Aggregate with EWMA (λ=0.3) + hysteresis (alert-on: 70, alert-off: 55)
6. Always show confidence level: LOW (<10 symptom days), MEDIUM (10–29), HIGH (30+)
7. Always show number of symptom days used in computation
8. Never claim causation — surface patterns only
9. Show mismatch days ("score was 85 but you felt fine") for transparency

---

## Daily briefing (AI-generated, not a chatbot)

Ashoo includes a **structured daily briefing** powered by the Anthropic Claude API
(claude-sonnet-4-6). It is NOT a free-form chatbot. It reads:
- Current risk score + factor breakdown
- Today's conditions vs. user's historical patterns
- User's registered medications and reminder rules
- Recent symptom log (last 7 days)

And generates a plain-English paragraph summary:
> "Today looks elevated for you — grass pollen is high and humidity is up,
> both of which have preceded your symptom days historically. If heading
> outside this afternoon, conditions are similar to days you've logged
> a severity 6+. You've registered your inhaler as a reminder for days
> like today. As always, consult your doctor for medical decisions."

**Hard rules for the briefing:**
- Always ends with "consult your doctor for medical decisions"
- Never says "take [medication]" or "skip [medication]"
- Never quantifies a dose or frequency
- Never claims to diagnose or predict with certainty
- Uses hedged language: "historically," "similar to," "may"
- If confidence is LOW, says "you're still early — keep logging for better insights"

---

## Medication system rules

1. User pre-registers their own medications. App provides a type dropdown
   (INHALER, ANTIHISTAMINE, EPINEPHRINE, NASAL_SPRAY, EYE_DROPS, OTHER)
   plus a free-text name field. App never suggests what to register.
2. Medication usage is tracked over time per log entry.
3. Reminders are time-aware (no alerts at 11pm unless user configures it).
4. Usage patterns are surfaced ("You've used your rescue inhaler 4 times
   this week — higher than your 3-month average of 1.2/week").
5. Every reminder includes the mandatory disclaimer — cannot be disabled:
   > "⚠️ This is a suggestion based on your own settings, not medical advice.
   > Always carry your prescribed medication. Consult your doctor."
6. Consent record required before any reminder feature is accessible.

---

## Data retention

- Raw hourly snapshots: **6-month rolling window** (auto-expired by TimescaleDB policy)
- Daily aggregates (continuous aggregate view): **kept indefinitely**
- Symptom logs: **kept indefinitely** (user's own data)
- Risk score history: **6-month rolling**
- Correlation results: **kept indefinitely** (recomputed, small rows)
- User export: available at any time as CSV (`GET /api/v1/export`)

---

## Technology stack (locked)

| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Java 21 LTS | Virtual threads stable, hireable |
| Framework | Spring Boot 3.5.x | Industry standard, virtual threads built-in |
| Concurrency | Virtual threads (`spring.threads.virtual.enabled=true`) | I/O-bound polling |
| Database | TimescaleDB (Postgres extension) | Time-series, plain SQL, Spring Data compatible |
| Migrations | Flyway | Expected baseline on any backend |
| HTTP client | Spring RestClient | Synchronous + virtual-thread-friendly |
| Scheduling | Spring @Scheduled + SimpleAsyncTaskScheduler | Virtual-thread-backed |
| Testing | JUnit 5 + Testcontainers | Real TimescaleDB in CI |
| Observability | Spring Boot Actuator + Micrometer | Health + metrics |
| API docs | springdoc-openapi | Auto Swagger UI at /swagger-ui.html |
| Containers | Docker + docker-compose | Local dev + CI |
| CI | GitHub Actions | Build + test on every push |
| AI briefing | Anthropic Claude API (claude-sonnet-4-6) | Daily briefing generation |
| Frontend | React + Vite + Tailwind CSS | Interactive UI, recruiter demo |
| Deployment | Fly.io | Always-on, Dockerfile-based, ~$2–5/mo |

**Explicitly deferred (do NOT add in V1):**
Kafka, microservices, Kubernetes, Spring WebFlux, Spring Cloud,
any ML framework, push notifications, Google Maps route optimization,
automatic GPS tracking, mobile app.

---

## Module structure

```
com.ashoo
├── ingestion          # API clients, scheduler, rate limiting, derived signals
│   ├── openmeteo      # Weather + AQ + pollen client
│   ├── openaq         # Ground-truth AQ client
│   └── airnow         # US AQI fallback
├── storage            # TimescaleDB entities + repositories
│   ├── entity         # All @Table classes
│   └── repository     # Spring Data JDBC repos
├── correlation        # Statistical engine
│   ├── normalizer     # Percentile normalization 0-100
│   ├── correlator     # Spearman + point-biserial + lag
│   ├── threshold      # Youden's J + sparse fallback
│   └── scorer         # Weighted aggregate + EWMA + hysteresis
├── briefing           # Claude API integration, prompt builder, disclaimer injector
├── alerting           # Risk score events, EWMA state, reminder evaluation
├── reminder           # Medication model, rules, consent, disclaimer guard
├── location           # Saved locations, recent searches, geocoding
├── export             # CSV export endpoint
├── api                # REST controllers, DTOs, OpenAPI config
└── common             # Config, constants, shared utils, AQI calculator
```

**V1/V2/V3 boundary:**

V1 (this build): Single user, OpenMeteo+OpenAQ+AirNow, symptom logging,
correlation engine (a+b), daily briefing, medication reminders, saved locations,
recent searches, interactive location query, data export, React frontend,
demo mode with 3 personas, Fly.io deployment.

V2: Multi-user + auth (Spring Security + JWT), 5-day risk forecasting,
US pollen (Google Pollen API), browser push notifications, caregiver sharing.

V3: Raspberry Pi + PMS5003 edge sensor, mobile app (React Native),
geographic risk heat map, route optimization (Google/Apple Maps API),
automatic GPS location tracking.

---

## Javadoc requirement (mandatory — do not skip)

The developer is learning Java through this project.
**Every method must have a Javadoc that teaches, not just labels.**

Required format:
```java
/**
 * One sentence: what this method does.
 *
 * One to three sentences: WHY this approach was chosen —
 * the design decision or Java/stats concept a learner should understand.
 *
 * @param name   what this parameter is and its expected range/format
 * @return       what is returned and what it means
 * @throws Type  when and why
 */
```

Never write "gets the value" or "sets the field."
Every Javadoc must explain something about the design.

---

## Safety checklist (run before every commit)

- [ ] No method claims to diagnose, prescribe, or recommend medication
- [ ] Every reminder surface includes the full mandatory disclaimer text
- [ ] Daily briefing ends with "consult your doctor for medical decisions"
- [ ] Briefing never says "take [drug]" or specifies dose/frequency
- [ ] No user-facing copy claims causation ("this WILL cause symptoms")
- [ ] Synthetic data labeled `data_origin = 'SEEDED_SYNTHETIC'` in DB
- [ ] Confidence level displayed alongside every correlation output
- [ ] Open-Meteo attribution present in API responses
- [ ] ConsentRecord checked before any reminder feature accessible
- [ ] Mismatch days visible in correlation transparency view

---

## Key references

- Open-Meteo AQ API: https://open-meteo.com/en/docs/air-quality-api
- Open-Meteo Historical API: https://open-meteo.com/en/docs/historical-weather-api
- OpenAQ v3 docs: https://docs.openaq.org
- EPA AQI Technical Doc: https://document.airnow.gov/technical-assistance-document-for-the-reporting-of-daily-air-quailty.pdf
- FDA General Wellness Guidance (Jan 6 2026): https://www.fda.gov/regulatory-information/search-fda-guidance-documents/general-wellness-policy-considerations-low-risk-devices
- TimescaleDB docs: https://docs.timescale.com
- Spring Boot virtual threads: https://docs.spring.io/spring-boot/reference/features/spring-application.html
- Testcontainers + Spring Boot: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Anthropic API docs: https://docs.anthropic.com
