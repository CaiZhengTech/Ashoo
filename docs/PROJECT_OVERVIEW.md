# Ashoo — Project Overview & Implementation Deep-Dive

> For interview preparation and technical walkthroughs.

---

## What Ashoo Does (The Elevator Pitch)

Most air quality apps answer "Is the air bad today?" Ashoo answers a different question: **"Is the air bad for *me* today, based on my own history?"**

Apps like AirNow give population-average AQI thresholds. A PM2.5 of 20 µg/m³ is "Good" by EPA standards — but for someone who consistently has symptoms at 12, that number means nothing. Ashoo learns your personal trigger fingerprint by correlating your self-logged symptom days against archived environmental data (air quality, pollen, weather, barometric pressure), then surfaces a personalized 0–100 **Personal Risk Index (PRI)** calibrated to your own bad days, not an average person's.

It offers a third path between two bad options people with respiratory allergies face today:
1. Take antihistamines every day "just in case" — over-reliance on medication
2. React only after symptoms appear, with no advance awareness

Ashoo offers **informed awareness**: know when conditions historically precede *your* bad days.

---

## The Technical Problem It Solves

The fundamental challenge is that the relationship between air quality and respiratory symptoms is **individual** — peer-reviewed literature (e.g., the 2025 Barcelona wearable-asthma feasibility study) finds no consistent short-term exposure-symptom link at the population level, precisely *because* the relationship is individual. Ashoo's architecture is built around this insight: every statistic is personal.

---

## Architecture Overview

Ashoo is a **Java 21 / Spring Boot 3.5** REST API backed by **TimescaleDB** (PostgreSQL with time-series extensions), paired with a **React + Vite** frontend. The data pipeline is:

```
External APIs (Open-Meteo, OpenAQ, AirNow)
    → Ingestion scheduler (hourly, virtual threads)
        → environmental_snapshot hypertable (TimescaleDB)
            → Correlation engine (Spearman, Youden, EWMA)
                → Personal Risk Index (0–100)
                    → React dashboard + Claude-generated briefing
```

---

## Layer-by-Layer Walkthrough

### 1. Ingestion Layer (`com.ashoo.ingestion`)

**What it does:** Polls three external APIs every hour and stores environmental readings.

**Data sources:**
- **Open-Meteo** — primary source. Provides weather (temperature, humidity, pressure, wind), air quality (PM2.5, PM10, O3, NO2, SO2, CO), and pollen (Europe-only via CAMS model). No API key required, CC BY 4.0 license, caching/archiving allowed.
- **OpenAQ v3** — ground-truth AQ sensor readings (optional, API key required).
- **EPA AirNow** — official US AQI (optional, API key required).

**Important constraint:** Open-Meteo's pollen data uses the Copernicus Atmosphere Monitoring Service (CAMS) model which covers Europe only. The demo uses Amsterdam to get pollen data. Sharon, MA (the default user location) gets air quality + weather but no pollen from Open-Meteo — a real limitation documented in the codebase.

**Derived signals:** At ingest time, the service computes additional signals that the raw APIs don't provide:
- **PM2.5 rate of change** — how fast particulates are rising/falling (directional signal)
- **3-hour pressure drop** — sudden barometric drops precede some respiratory events
- **24-hour cumulative PM2.5** — total exposure, not just current reading
- **Computed AQI** — EPA breakpoints applied to PM2.5 (post-May 2024 breakpoints: Good 0–9.0, Moderate 9.1–35.4, etc.)
- **Thunderstorm flag** — a heuristic boolean (high humidity + pressure drop + wind spike)

**Why virtual threads matter here:** The ingestion service is purely I/O-bound — it's waiting on network calls to three external APIs, then waiting on database writes. With Java 21 virtual threads (`spring.threads.virtual.enabled=true`), thousands of these blocked calls can be in-flight simultaneously on a handful of OS threads. No reactive programming needed, no `Mono`/`Flux` complexity — the code looks synchronous, the runtime handles concurrency.

---

### 2. Storage Layer (`com.ashoo.storage`)

**Why TimescaleDB?** TimescaleDB extends PostgreSQL with hypertables — internally, it automatically partitions time-series data into chunks by time range. This makes range queries ("give me all PM2.5 readings from the last 30 days") dramatically faster than on a plain table because the query only touches the relevant time chunks, not the entire table. It's still plain SQL and fully compatible with Spring Data JDBC.

**Why Spring Data JDBC (not JPA)?** JPA adds hidden complexity: lazy loading, the persistence context, N+1 query problems, and non-obvious transaction behavior. For a data-intensive app where query performance matters, JDBC is more transparent. You write the SQL, you know exactly what runs.

**Schema (8 Flyway migrations):**

| Table | Type | Retention |
|-------|------|-----------|
| `environmental_snapshot` | TimescaleDB hypertable | 6 months (auto-expired) |
| `symptom_log` | Regular table | Indefinite (user's own data) |
| `risk_score_history` | TimescaleDB hypertable | 6 months |
| `saved_location` | Regular table | Until user deletes |
| `recent_search` | Regular table | Rolling 10 (auto-pruned) |
| `medication` | Regular table | Until user removes |
| `reminder_rule` | Regular table | Soft-deleted |
| `consent_record` | Regular table | Indefinite |
| `correlation_result` | Regular table | Indefinite |
| `briefing_log` | Regular table | Indefinite (caches today's briefing) |
| `daily_aggregate` | Continuous aggregate view | Indefinite |

The **continuous aggregate** (`daily_aggregate`) is a TimescaleDB feature that materializes daily rollups automatically as new hourly data arrives. This makes the long-range trend chart (3-month, 6-month views) fast without computing aggregates on the fly.

---

### 3. Correlation Engine (`com.ashoo.correlation`)

This is the intellectual core of Ashoo. It uses **transparent, honest statistics** — not machine learning. Every number it produces (Spearman rho, threshold, weight, confidence level) is surfaced to the user. The design principle: no black box.

**The full pipeline:**

#### Step 1: Normalization
Before any correlation is computed, each environmental factor is converted to a **0–100 personal percentile rank** against the user's own 180-day history. A PM2.5 of 20 µg/m³ might be the 90th percentile for someone who usually sees 3–8 µg/m³ (Morgan, high sensitivity, Amsterdam pollen season), or the 40th percentile for someone in a consistently polluted area.

This normalization is what makes the correlation *personal*. You're not comparing a factor to EPA thresholds — you're comparing it to YOUR baseline.

#### Step 2: Correlation
For each factor, we compute two statistics:
- **Spearman's rank correlation coefficient (rho)**: Non-parametric correlation between the factor's percentile rank and symptom severity (0–10). Non-parametric means it handles non-normal distributions, which environmental data frequently has (PM2.5 can have extreme outliers).
- **Point-biserial correlation**: Correlation between the factor and a binary "had symptoms / didn't" label.

Both are tested at **0, 24, 48, and 72-hour lags** because triggers don't always cause immediate symptoms. High pollen today might cause symptoms tomorrow. The best lag per factor is kept.

The lag analysis is why Ashoo can surface things like: "Your PM2.5 correlation is strongest at 24 hours" — which means you tend to feel effects the day *after* high pollution, not the same day.

#### Step 3: Thresholds (Youden's J)
For each factor, we find the reading above which you're most likely to have symptoms. This is done with **Youden's J statistic** (J = sensitivity + specificity − 1), which finds the cut-point that maximizes the sum of true positive rate and true negative rate simultaneously.

In practice: we iterate over candidate thresholds, compute how well each one separates "bad days" from "good days" for this factor, and pick the best one.

**Sparse fallback:** Youden's J requires at minimum 10 symptom days to be reliable. Below that, we fall back to the **75th percentile of symptom-day readings** for that factor — a simpler but still meaningful threshold.

This is why the confidence level matters:
- **LOW** (< 10 symptom days): Thresholds aren't learned yet, model is unreliable
- **MEDIUM** (10–29 symptom days): Youden's J thresholds in use, building confidence
- **HIGH** (30+ symptom days): Strong personal model

#### Step 4: Scoring — The Personal Risk Index

This was the most technically nuanced part of the implementation. The naive approach (weighted average of factor percentiles) fails for personalization: in the same conditions, a broadly-sensitive person (many factors above their thresholds) and a narrowly-sensitive person (one factor very high) can produce similar weighted averages.

The solution blends **two signals at 50/50**:

**Intensity (50%)** — threshold-adjusted weighted mean:

```java
// personalScoreInput() in RiskScoringService
if (aboveThreshold) {
    return 60 + 0.4 * percentile;  // floor of 60 once above YOUR threshold
} else {
    return 0.35 * percentile;       // dampened below YOUR threshold
}
```

The key insight: once a factor crosses YOUR personal threshold, it's a meaningful signal regardless of its absolute level. The 60-point floor means "you crossed your threshold" always registers strongly. Below threshold, the factor is dampened so it contributes less noise.

**Breadth (50%)** — count of meaningful active triggers:

```java
// meaningfulActive() in RiskScoringService
long count = contributions.stream()
    .filter(c -> c.aboveThreshold() && c.weight() >= averageWeight)
    .count();
double breadth = Math.min(100.0, count * 18.0);
```

"Meaningful" means both above YOUR threshold AND carrying at least average model weight. This prevents sparse models (Morgan in the first weeks of use) from inflating breadth through low-confidence, noisy factors.

The 18-point increment per active trigger means: 1 trigger = 18, 2 = 36, 3 = 54, 4 = 72, 5 = 90, 6+ = 100 (capped). This rewards Morgan's multi-trigger sensitivity in a principled way.

**Final blend:**
```java
double raw = 0.5 * intensityScore + 0.5 * breadthScore;
```

**Why this matters for the demo:** In identical air conditions, Morgan (many low thresholds) reliably scores higher than Jordan (one medium threshold) who scores higher than Alex (one high threshold). Same data, different personal baselines, different scores — that's the whole thesis.

#### Step 5: Smoothing and Hysteresis

The raw blended score is noisy — it changes every hour as new data arrives. Two mechanisms stabilize it:

**EWMA (Exponentially Weighted Moving Average, lambda = 0.3):**
```
smoothed = 0.3 * raw + 0.7 * previousSmoothed
```
Recent readings count more than old ones, but not so much that the score jerks on every hourly update. Lambda = 0.3 is a moderate decay rate — roughly 3–4 hours of history dominate.

**Hysteresis (alert-on at 70, alert-off at 55):**
Once the score enters "alert" territory (≥ 70), it stays alert until it drops below 55. This prevents the score from toggling back and forth when hovering near the 70 boundary — which would make reminders fire and cancel in rapid succession.

---

### 4. Location Matching

An easy-to-miss but important detail: the correlation engine must only use symptom logs from the same location as the environmental data. A symptom day logged in Tokyo against Amsterdam environmental data would add noise, not signal.

`filterToTrackedLocation()` in `CorrelationService` normalizes both the symptom log's city and the environmental snapshot's city (lowercased, first comma segment), then filters logs to only those that match. Logs with no city are kept for backward compatibility.

---

### 5. Briefing (`com.ashoo.briefing`)

The daily briefing is generated by the **Anthropic Claude API** (`claude-sonnet-4-6`). It's NOT a chatbot — it's a structured generation with a specific prompt.

`BriefingContextBuilder` assembles:
- Current risk score, label, confidence level
- Per-factor breakdown (value, percentile, whether above threshold)
- Last 7 days of symptom history
- Registered medications and reminder rules
- Today's date and location

This context is passed to Claude with a detailed system prompt that enforces:
- Advisory-only language ("may," "historically," "similar to")
- No medication dosage or timing
- Mandatory closing: "consult your doctor for medical decisions"
- LOW confidence message if < 10 symptom days

The briefing is cached per user per day in `briefing_log` — so the API is called at most once per user per day, not once per dashboard load.

**One bug fixed in this session:** `BriefingContextBuilder` was calling `currentBreakdown(userId, userId)` for demo personas, which tried to read environmental data stored under the persona's id — but all environmental data lives under the default user id (`ashoo-user`). Fixed to `currentBreakdown(userId, DemoUsers.ENV_USER)`.

---

### 6. Medications and Reminders (`com.ashoo.reminder`)

**The consent architecture** is structural, not just UI: `ConsentGuard` checks the `consent_record` table on every request to the medication and reminder endpoints. Even if someone bypassed the frontend consent checkbox, the backend enforces it on every call.

**Reminders are purely the user's own words echoed back.** The user writes their note ("Pack my inhaler before heading out"). When conditions cross their chosen threshold at their chosen time, Ashoo shows that exact note. Ashoo never invents content. The mandatory disclaimer is appended at evaluation time, defined as a compile-time constant, and cannot be disabled:

> "This is a suggestion based on your own settings, not medical advice. Always carry your prescribed medication. Consult your doctor."

**Time-awareness:** Reminders include a "remind me at" time. Nothing fires outside that window, so you won't get a 3am ping. Internally stored as a start time with end = 23:59, supporting overnight ranges via the `withinWindow()` logic in `ReminderEngine`.

---

### 7. Demo System

The three personas have deliberately different profiles so the correlation engine produces visibly distinct fingerprints:

| Persona | Location | Trigger | Why it was designed this way |
|---------|----------|---------|------------------------------|
| Alex | Los Angeles, CA | PM2.5 > 26 only | Stays at LOW confidence — shows the "keep logging" story |
| Jordan | Atlanta, GA | Pollen > 12 | Seasonal pattern, MEDIUM confidence — the classic allergy story |
| Morgan | Houston, TX | PM2.5 > 11 OR pollen > 8 OR pressure drop > 3 | HIGH confidence, many triggers — richest demo |

All three "live" in different cities, but their environmental data is shared (Amsterdam, for pollen availability). The city name is cosmetic — the symptom logs carry the persona's city, creating a realistic demo story without faking environmental readings.

**The scoring challenge:** Getting Morgan to reliably score higher than Jordan in the same conditions took several iterations. The root issue: a weighted average of factor percentiles collapses broad sensitivity vs. narrow sensitivity into similar numbers. The breadth component (counting meaningful active triggers) is what solved it. Morgan has 3–4 factors above her low thresholds; Jordan has 1 factor above his moderate threshold. Same air, different personal scores.

---

### 8. Frontend

**React Query (TanStack Query v5)** manages all server state. Every API call is a query with a key, automatic caching, refetch-on-focus, and stale-while-revalidate. This means the dashboard feels instant on navigation because previous data is shown while fresh data loads in the background.

**PersonaContext** uses React Context to track which persona is being viewed. It exposes `userParam` (the query param sent to APIs) and `isDemo` (controls the amber demo banner). Personal actions (logging symptoms, medications, consent) always operate on the real user regardless of which persona is being viewed.

**Key UI components:**

- **RiskBadge** — SVG arc gauge with smooth CSS transition animation. The fill is driven by `stroke-dashoffset` on a circular path.
- **ConditionsReadout** — Per-pollutant gauge bars with color ramps. `airScale()` uses documented EPA ceiling values (PM2.5: 55.4, O3: 200, etc.) to position the bar proportionally. Color interpolates green→yellow→orange→red.
- **FactorBreakdownList** — Shows real measured value + unit, percentile word label, colored bar. Unit data flows from the `Factor` enum's `unit` field → `FactorContribution` record → `RiskCurrentResponse.FactorView` DTO → frontend.
- **RiskOverTimeChart** — Recharts line chart with five severity band `ReferenceArea` overlays and hysteresis threshold dashed lines.
- **IntroGate** — Session-based video overlay (not a route). `preload="auto"` + `onCanPlay` fade-in avoids the blank placeholder frame. Falls back to animated CSS blobs if the video is missing.
- **ToastContext** — Lightweight notification system via React Context. Auto-dismisses at 3.2s. Every mutation (log saved, medication added, reminder created) fires a toast.

---

## Changes Made (This Session)

### 1. Persona locations
Each demo persona now shows a distinct US city:
- You → Sharon, MA (the real development default)
- Alex → Los Angeles, CA
- Jordan → Atlanta, GA  
- Morgan → Houston, TX

The city names are cosmetic — env data is shared from Amsterdam — but they create a more realistic demo story and address the confusion about why Sharon, MA was showing pollen data it shouldn't have.

**Files:** `PersonaContext.jsx`, `Layout.jsx` (demo banner), `SeedDemoService.java`, `DemoController.java`

### 2. Single reminder time
Replaced the From/To time window with a single "Remind me at" time. Internally `start = chosen time`, `end = 23:59`. The UX now matches the mental model: "at 8am, if it's a high-risk day, show me my note."

**Files:** `RemindersPage.jsx`

### 3. Reminders next to PRI card
Moved `CurrentReminders` from the bottom of the right column to directly below the PRI hero card. Medication reminders are now the first contextual content you see after your risk score — which is where they belong conceptually.

**Files:** `Dashboard.jsx`

### 4. README.md
Rewrote the project README with full documentation: setup instructions, architecture, all API endpoints, configuration reference, data sources, and deployment steps.

### 5. PROJECT_OVERVIEW.md (this file)
Interview-prep document explaining every design decision in depth.

---

## How to Talk About This in an Interview

**"What is Ashoo?"**
> A personal environmental health correlation engine. It learns your specific trigger fingerprint — what air quality, pollen, and weather conditions precede *your* symptom days — and gives you a personalized 0–100 risk score calibrated to your own history, not population averages.

**"What's technically interesting about it?"**
> The scoring algorithm. The naive approach — a weighted average of environmental factor percentiles — fails to distinguish a broadly-sensitive person from a narrowly-sensitive one in the same conditions. I solved it by blending two signals: intensity (threshold-adjusted factor scores, where crossing YOUR personal threshold always registers strongly) and breadth (count of how many of your meaningful triggers are simultaneously active). This makes the score genuinely personal.

**"Why TimescaleDB?"**
> Time-series queries on environmental data — "give me all PM2.5 readings for the last 30 days" — are dramatically faster with hypertable time-partitioning than on plain PostgreSQL. TimescaleDB also supports continuous aggregates (materialized daily rollups that update automatically as new hourly data arrives), which powers the long-range trend charts. And it's still plain SQL and Spring Data JDBC — no new query language or ORM layer to learn.

**"Why Java 21 / virtual threads?"**
> The app is I/O-bound: polling three external APIs every hour, waiting on database writes. Virtual threads let hundreds of these blocked calls run concurrently without configuring thread pool sizes or adopting reactive programming (WebFlux, Mono/Flux). The code looks synchronous, the runtime handles the concurrency. For a solo project with no dedicated ops support, that simplicity matters.

**"What's the hardest problem you solved?"**
> Getting the personal risk scoring to produce meaningfully different scores for different sensitivity profiles in the same environmental conditions. The first few approaches — bare weighted averages, threshold penalties — all converged toward similar scores for all personas in the same air. The breakthrough was separating intensity (how much each factor is elevated above YOUR threshold) from breadth (how many of YOUR triggers are active at once), and blending them at 50/50. Morgan has five things slightly above her low thresholds; Alex has one thing well above his high threshold. Same air, different personal risk.

**"What are the safety constraints?"**
> Non-negotiable: Ashoo is not a medical device. Reminders are the user's own pre-configured notes echoed back — Ashoo never invents content. The mandatory disclaimer is a compile-time constant appended at evaluation time, not stored with the rule (so it can never be edited out). The briefing always closes with "consult your doctor for medical decisions" — enforced in both the prompt and post-validation. The consent gate is checked server-side on every medication and reminder request, not just once at UI setup. These aren't conventions — they're architecture.

**"What would V2 add?"**
> Multi-user support (Spring Security + JWT), US pollen via the Google Pollen API, 5-day risk forecasting, and browser push notifications for reminders. The correlation engine is already designed to be per-user so adding auth doesn't require redesigning the data model.

---

## Attribution

Weather and air quality data by **Open-Meteo.com (ECMWF/CAMS)**.

Ashoo is not a medical device and provides no medical advice. Always carry your prescribed medication and consult your doctor for medical decisions.
