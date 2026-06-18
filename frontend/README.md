# Ashoo — Frontend

React + Vite + Tailwind UI for the Ashoo personal environmental health correlation
engine. Talks to the Spring Boot backend's REST API (`/api/v1/**`).

> Ashoo is not a medical device. It does not diagnose, treat, or prescribe.
> Data by Open-Meteo.com (ECMWF/CAMS).

## Stack

| Concern        | Choice                          | Why                                            |
|----------------|---------------------------------|------------------------------------------------|
| Build/dev      | Vite 5                          | Fast HMR, simple static output                 |
| UI             | React 18                        | Component model, matches CLAUDE.md             |
| Styling        | Tailwind CSS 3                  | Consistent spacing/colors, no CSS sprawl       |
| Server state   | TanStack Query 5                | Caching + loading/error states for free        |
| HTTP           | axios                           | One typed client mirroring the API contract    |
| Charts         | Recharts                        | Risk-over-time area chart                       |
| Routing        | react-router-dom 6              | Five top-level pages                            |

## Pages

- **Dashboard** — Personal Risk Index gauge, AI daily briefing, factor breakdown,
  30-day trend, reminders matching current conditions.
- **Log** — Add/edit/delete symptom days (severity 0–10, notes, location, meds);
  editing auto-recalibrates the model.
- **Insights** — Learned correlations (Spearman, personal threshold, weight,
  confidence) plus mismatch days for honest transparency.
- **Care** — Consent gate → register your own medications + usage stats, and
  condition-based reminder rules. Every reminder carries the mandatory disclaimer.
- **Places** — Saved locations (geocoded by city name), ad-hoc conditions lookup,
  recent searches, demo-data seeding, and CSV export.

## Run locally

The backend must be running first (default `http://localhost:8080`). See the root
`README.md` for backend + TimescaleDB setup.

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

In dev, Vite proxies `/api` and `/actuator` to the backend, so the browser sees a
single origin (no CORS needed). To point at a backend on another port:

```bash
VITE_PROXY_TARGET=http://localhost:8081 npm run dev
```

## Production build

```bash
npm run build         # outputs static site to dist/
npm run preview       # serve the built site locally
```

For a hosted frontend (e.g. Vercel) calling the deployed backend, set the API
origin at build time (the browser calls it directly; backend CORS allows it via
`ashoo.cors.allowed-origins`):

```bash
# frontend/.env  (see .env.example)
VITE_API_BASE_URL=https://your-backend.fly.dev
```

## How it connects to the backend

- `src/api/client.js` — axios instance; base URL is `VITE_API_BASE_URL` (prod) or
  empty (dev proxy). Normalizes errors into one human-readable message.
- `src/api/endpoints.js` — one named function per backend route. The single source
  of truth for the API contract; update here if a route changes.
- The backend is single-user (`ashoo-user`); there is no auth in V1.

## Backend requirement

Cross-origin calls are enabled by `com.ashoo.common.WebCorsConfig` on the backend.
Add your production frontend origin via the `ashoo.cors.allowed-origins` property
(comma-separated). Localhost dev ports (5173, 4173) are allowed by default.
