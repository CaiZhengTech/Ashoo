# CLAUDE.md — Aura Engine
### Personal Environmental Health Correlation Engine

> **Read this file at the start of every session.**
> It is the single source of truth for every decision made about this project.
> Never invent context. If something is not here, ask before assuming.

---

## What this project is

Aura Engine is a **personal environmental health correlation engine** built in Java and Spring Boot.

It answers one question that no existing app answers well:
> *"What does the air do to ME specifically — not to the average person?"*

Apps like AirNow and IQAir report population-average AQI thresholds. Aura learns the user's
**personal trigger fingerprint** by correlating their self-logged symptom days against archived
environmental data — air quality, pollen, weather, and pressure — to surface personalized risk
scores and pattern-based reminders.

### The problem it solves
People with asthma, hay fever, or respiratory allergies currently have two bad options:
1. Take antihistamines or use an inhaler every day "just in case" (over-reliance on medication,
   and first-generation antihistamines like Benadryl carry documented long-term cognitive risks
   after ~83 years on the market, only identified in 2015).
2. Ignore environmental conditions entirely and react only after symptoms appear.

Aura offers a third path: **informed awareness** — surface the conditions that historically
precede YOUR bad days, so you carry your inhaler when it genuinely matters, not reflexively.

### What it is NOT
- It is NOT a medical device.
- It does NOT diagnose, treat, or prescribe anything.
- It does NOT tell the user to take or skip medication.
- It is NOT a replacement for carrying prescribed medication or seeing a doctor.
- Every reminder it surfaces is the user's OWN pre-configured note echoed back when conditions
  match a learned pattern. The app never makes the content of that reminder.

This framing is non-negotiable. It keeps the product on the right side of the FDA's
General Wellness guidance (updated Jan 6, 2026) and, more importantly, keeps users safe.

---

## The user (V1)

Single user. No multi-tenancy in V1. The `user_id` column exists on every domain table
(defaulted to `"default-user"`) so V2 multi-user support is a query filter, not a schema
migration. Never add multi-user logic in V1 — that is explicitly V2 scope.

---

## Data sources (locked decisions — do not change without updating this file)

| Source | What it provides | Free? | Key required? | Storage permitted? |
|--------|-----------------|-------|--------------|-------------------|
| **Open-Meteo** | Weather (temp, humidity, pressure, wind, gusts), Air quality (PM2.5, PM10, O₃, NO₂, SO₂, CO), Pollen (Europe only) | ✅ Yes (non-commercial) | ❌ No key needed | ✅ CC BY 4.0 — storage explicit |
| **OpenAQ v3** | Ground-truth air quality readings from real stations | ✅ Free tier | ✅ X-API-Key header | ✅ Open license |
| **US EPA AirNow** | Official US AQI by reporting area | ✅ Free | ✅ API key | ✅ Government open data |

### Critical constraints on data sources
- **Pollen is Europe-only on Open-Meteo.** The demo location MUST be a European city
  (e.g., London, Amsterdam, Berlin) for pollen to be available. V2 can add Google Pollen API
  for US coverage (requires billing + careful derived-index storage to respect caching terms).
- **WAQI/aqicn forbids archiving** — do NOT use it in this project.
- **PurpleAir is now paid** — do NOT use it.
- **BreezoMeter is now Google** — covered under Google Pollen API concerns above.
- **Mold has no free real-time API.** Omit as a live factor. If included later, label it
  clearly as a humidity/temperature-derived PROXY, never a measurement.

### Required attribution (CC BY 4.0)
The app's API responses and any UI must include:
> "Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)"

---

## The factor set (environmental inputs to the correlation engine)

These are the dimensions tracked per hourly reading. All stored in the `environmental_snapshot`
hypertable in TimescaleDB.

**Air quality (from OpenAQ + Open-Meteo AQ API):**
- `pm25` — µg/m³ — strongest asthma evidence
- `pm10` — µg/m³
- `o3` — µg/m³ — second-strongest asthma evidence
- `no2` — µg/m³ — most robust in multi-pollutant models
- `so2` — µg/m³
- `co` — µg/m³

**Pollen (from Open-Meteo Air Quality API — Europe only):**
- `pollen_alder` — grains/m³ (spring tree allergen)
- `pollen_birch` — grains/m³ (major spring tree allergen)
- `pollen_grass` — grains/m³ (summer, strongest thunderstorm-asthma connection)
- `pollen_mugwort` — grains/m³ (late summer weed)
- `pollen_olive` — grains/m³ (Mediterranean spring)
- `pollen_ragweed` — grains/m³ (autumn, classic hay fever)

**Meteorological (from Open-Meteo Weather API):**
- `temperature_c` — °C
- `relative_humidity_pct` — % (dust mite risk proxy above 50%)
- `pressure_msl_hpa` — hPa — barometric pressure at mean sea level
- `wind_speed_ms` — m/s
- `wind_gusts_ms` — m/s — thunderstorm-asthma indicator

**Derived signals (computed at ingest time, stored alongside raw):**
- `pm25_rate_of_change` — Δpm25 vs prior reading (spike detection)
- `pressure_drop_3h_hpa` — pressure change over 3-hour window (storm proxy)
- `thunderstorm_risk_flag` — boolean: grass_pollen > threshold AND wind_gusts > 10 m/s
  AND humidity > 70% (heuristic ONLY — label explicitly, never claim validated)
- `cumulative_pm25_24h` — rolling 24-hour PM2.5 load (lagged burden)
- `aqi_computed` — computed from pm25 using verified EPA 2024 breakpoints

### EPA AQI breakpoints (post–May 6, 2024 — verified, locked)
These changed in 2024. The PM2.5 "Good" ceiling dropped from 12.0 to 9.0 µg/m³.

| Category | AQI | PM2.5 24h (µg/m³) |
|----------|-----|-------------------|
| Good | 0–50 | 0.0–9.0 |
| Moderate | 51–100 | 9.1–35.4 |
| Unhealthy for Sensitive Groups | 101–150 | 35.5–55.4 |
| Unhealthy | 151–200 | 55.5–125.4 |
| Very Unhealthy | 201–300 | 125.5–225.4 |
| Hazardous | 301–500 | 225.5–325.4 |

AQI formula: `I_p = ((I_Hi - I_Lo) / (BP_Hi - BP_Lo)) * (C_p - BP_Lo) + I_Lo`

---

## The correlation engine (how it learns)

This is NOT a machine-learning model. It is honest, transparent statistics on a single user's data.
Using the word "AI" or "machine learning" to describe this to users would be misleading — avoid it.

### What it does, step by step

1. **Normalize each factor to 0–100 (personal percentile rank)**
   Convert each raw factor value (µg/m³, hPa, grains/m³, etc.) to the user's personal
   percentile rank within their own historical distribution. So "PM2.5 = 95" means
   "PM2.5 today is at the 95th percentile of what this user has historically seen."
   This is unit-free and fully personalized.

2. **Correlate with symptom log (with time lags)**
   For each factor, compute point-biserial correlation against the binary symptom log
   (symptom day = 1, no symptom = 0), tested at lags 0, 24h, 48h, 72h.
   Also compute Spearman rank correlation against graded severity (0–3).
   Use time-lagged cross-correlation to find the lag at which each factor peaks.

3. **Learn personal thresholds**
   When sufficient data exists (minimum 10 symptom days + 10 non-symptom days),
   use Youden's J = (Sensitivity + Specificity - 1) on a ROC curve to pick the
   personal cut-point for each factor. Report bootstrap confidence intervals.
   When data is sparse, fall back to the 75th percentile of the user's symptom-day
   readings as the threshold estimate, labeled "estimated (low confidence)."

4. **Aggregate into a current risk score 0–100**
   Weighted sum of normalized factor values, where weights = |Spearman correlation|
   for each factor (factors that better predict the user's symptoms get more weight).
   Apply EWMA smoothing (λ=0.3) then threshold+hysteresis (alert on at 70, off at 55).

5. **Pattern surfacing (not causation claims)**
   Surface the factors most correlated with the user's symptom days as plain statements:
   "On days you logged symptoms, PM2.5 tended to be above X and humidity above Y."
   Never claim causation. Never say "this WILL cause symptoms."

### Statistical honesty requirements
- Always show confidence level ("low / medium / high") based on data quantity.
- Show "Insufficient data — keep logging" when < 10 symptom days available.
- Multiple-comparisons caveat: with ~13 factors × 4 lag windows = 52 tests,
  at least 2–3 false positives are expected by chance. Communicate this honestly.
- Cite the Barcelona 2025 feasibility study (13 adults, 6 months, wearable sensors)
  as the honesty benchmark: even rigorous small-n studies find no consistent
  short-term exposure–symptom link. The system finds PATTERNS, not proven triggers.

---

## Medication reminder (safety-critical rules)

The reminder system is ADVISORY ONLY. These rules are non-negotiable:

1. The USER pre-registers their own current medications/remedies. The app provides
   a free-text field and a type enum (INHALER, ANTIHISTAMINE, EPINEPHRINE, OTHER).
   The app never suggests a medication. The app never populates this field.

2. The USER defines their own reminder rules: "when my risk score exceeds X,
   show me this note." The app only echoes back the user's own note.

3. Every reminder surface MUST include this exact disclaimer:
   > "⚠️ This is a suggestion based on your own settings, not medical advice.
   > Always carry your prescribed medication. Consult your doctor for health decisions."

4. The app NEVER says "take your inhaler," "skip your medication," or anything
   that implies a dosing or treatment decision. It says "you configured a reminder
   for conditions like today" and shows the user's own note.

5. Onboarding MUST include explicit consent acceptance (stored as ConsentRecord
   with timestamp) before any reminder feature is accessible.

6. The FDA General Wellness guidance (Jan 6, 2026) is the regulatory frame.
   Key disqualifying features to avoid: disease-specific claims, clinical thresholds
   presented as recommendations, treatment guidance, diagnostic language.

---

## Technology stack (locked — do not change without updating this file)

| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Java 21 (LTS) | Modern, virtual threads stable, hireable |
| Framework | Spring Boot 3.5.x | Industry standard, virtual threads built-in |
| Concurrency | Virtual threads (`spring.threads.virtual.enabled=true`) | I/O-bound polling workload — correct fit |
| Database | TimescaleDB (Postgres extension) | Time-series hypertables, plain SQL, Spring Data compatible |
| Migrations | Flyway | Baseline — expected on any backend project |
| Testing | JUnit 5 + Testcontainers | Integration tests against real TimescaleDB — high resume signal |
| Observability | Spring Boot Actuator + Micrometer | Metrics endpoint, health checks |
| API docs | springdoc-openapi (Swagger UI) | Auto-generated, accessible at /swagger-ui.html |
| Containerization | Docker + docker-compose | App + TimescaleDB local dev |
| CI | GitHub Actions | Build + test + Testcontainers on every push |
| Deployment | Fly.io (Dockerfile-based, ~$2/mo) | Cheapest always-on option for a Spring Boot app |
| HTTP client | Spring RestClient (synchronous, virtual-thread-friendly) | Simple, no reactive complexity needed |
| Scheduling | Spring @Scheduled + SimpleAsyncTaskScheduler | Virtual-thread-backed task execution |

### Explicitly deferred (do NOT add these in V1)
- Kafka / event streaming — no multi-producer need at single-user scale
- Microservices — modular monolith is correct at this scale
- Kubernetes — overkill for one container
- Spring WebFlux / reactive — adds complexity with no benefit (virtual threads solve the I/O problem)
- Spring Cloud — nothing to discover/coordinate at single-node scale
- Any ML framework (TensorFlow, PyTorch, DL4J) — honest statistics, not ML

---

## Module structure (modular monolith)

```
com.aura.engine
├── ingestion          # API clients, schedulers, rate limiting
│   ├── openmeteo      # Open-Meteo weather + AQ + pollen client
│   ├── openaq         # OpenAQ v3 client
│   └── airnow         # EPA AirNow fallback client
├── storage            # TimescaleDB entities, repositories, schema
│   ├── entity         # EnvironmentalSnapshot, SymptomLog, Medication, etc.
│   └── repository     # Spring Data repositories
├── correlation        # The statistical engine
│   ├── normalizer     # Percentile normalization 0-100
│   ├── correlator     # Point-biserial, Spearman, lagged cross-correlation
│   ├── threshold      # Youden's J, ROC, percentile fallback
│   └── scorer         # Weighted aggregation, EWMA, hysteresis
├── alerting           # Risk score thresholds, EWMA state, event generation
├── reminder           # Medication model, rule engine, consent, disclaimer
├── api                # REST controllers, DTOs, OpenAPI config
└── common             # Config, constants, shared utilities
```

Every domain table has a `user_id` column (defaulted to `"default-user"` in V1).
This means V2 multi-tenancy = add auth + filter queries by user_id. No schema migration needed.

---

## V1 / V2 / V3 boundary (do not cross these in the current build)

### V1 (this build — 7–14 days)
- Single user
- Ingest Open-Meteo + OpenAQ
- Store all readings in TimescaleDB
- Symptom logging API (user logs their own days)
- Correlation engine: (a) pattern surfacing, (b) current risk score 0–100
- Pattern-based medication reminders (user-defined rules + disclaimer)
- REST API with OpenAPI docs
- Docker + GitHub Actions CI + Fly.io deployment

### V2 (next release — after V1 ships)
- Multi-user + authentication (Spring Security + JWT or OAuth2)
- 5-day risk forecasting (using Open-Meteo forecast endpoint)
- US pollen (Google Pollen API with derived-index storage strategy)
- Caregiver/family sharing (read-only view of another user's risk score)
- Push notifications (beyond in-app reminders)

### V3 (later)
- Raspberry Pi + PMS5003 edge sensor node (posts to the same ingestion API)
- Indoor humidity/PM as separate "indoor environment" data stream
- Mobile app (the backend API is already the interface — V3 is a frontend)

---

## Javadoc requirement (important for this developer)

**Every method in this codebase MUST have a Javadoc comment.** The developer is learning
Java through this project, so Javadocs serve as inline teaching, not just documentation.

Required format for every method:
```java
/**
 * One-sentence summary of what this method does.
 *
 * Explanation of WHY it does it this way — the design decision or concept
 * that a Java learner should understand from this function.
 *
 * @param paramName  what this parameter represents and its expected range/format
 * @param paramName2 same for each parameter
 * @return           what is returned and what it means
 * @throws SomeException when and why this is thrown
 */
```

Do NOT write generic Javadocs ("gets the value," "sets the field"). Every Javadoc should
teach something about what the code is doing and why that approach was chosen.

---

## Safety checklist (run before every commit)

- [ ] No method claims to diagnose, prescribe, or recommend medication
- [ ] Every reminder surface includes the full disclaimer text
- [ ] No user-facing language says "this WILL cause symptoms" or implies causation
- [ ] Synthetic/seeded data is labeled with `dataOrigin = SEEDED_SYNTHETIC` in the DB
- [ ] Confidence level is shown alongside every correlation output
- [ ] Open-Meteo attribution is present in API responses
- [ ] ConsentRecord is checked before any reminder feature is accessible

---

## Key references

- Open-Meteo Air Quality API: https://open-meteo.com/en/docs/air-quality-api
- Open-Meteo Historical API: https://open-meteo.com/en/docs/historical-weather-api
- OpenAQ v3 API: https://docs.openaq.org
- EPA AQI Technical Assistance Document: https://document.airnow.gov/technical-assistance-document-for-the-reporting-of-daily-air-quailty.pdf
- FDA General Wellness Guidance (Jan 6, 2026): https://www.fda.gov/regulatory-information/search-fda-guidance-documents/general-wellness-policy-considerations-low-risk-devices
- TimescaleDB docs: https://docs.timescale.com
- Spring Boot virtual threads: https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads
- Testcontainers + Spring Boot: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
