// Shared formatting + domain helpers. Pure functions, no React, easy to reason
// about and reuse across cards.

/**
 * Maps a 0-100 Personal Risk Index to its LOCKED tier metadata.
 *
 * The thresholds and colors mirror the CLAUDE.md risk scale exactly. We return
 * Tailwind class fragments alongside the raw hex so components can style either
 * via utility classes (badges) or inline (SVG charts) without re-deriving the
 * tier. PRI is personal: 80 means "matches YOUR worst days," not a population AQI.
 *
 * @param {number} score 0-100 smoothed risk score
 * @returns tier descriptor with key, label, hex, and Tailwind helpers
 */
export function riskTier(score) {
  const s = Number(score) || 0;
  if (s <= 20)
    return { key: 'great', label: 'Great', hex: '#16a34a', emoji: '🟢', text: 'text-tier-great', bg: 'bg-tier-great', soft: 'bg-emerald-50 text-emerald-700 border-emerald-200' };
  if (s <= 40)
    return { key: 'moderate', label: 'Moderate', hex: '#eab308', emoji: '🟡', text: 'text-tier-moderate', bg: 'bg-tier-moderate', soft: 'bg-yellow-50 text-yellow-700 border-yellow-200' };
  if (s <= 60)
    return { key: 'elevated', label: 'Elevated', hex: '#f97316', emoji: '🟠', text: 'text-tier-elevated', bg: 'bg-tier-elevated', soft: 'bg-orange-50 text-orange-700 border-orange-200' };
  if (s <= 80)
    return { key: 'high', label: 'High', hex: '#ef4444', emoji: '🔴', text: 'text-tier-high', bg: 'bg-tier-high', soft: 'bg-red-50 text-red-700 border-red-200' };
  return { key: 'severe', label: 'Severe', hex: '#a855f7', emoji: '🟣', text: 'text-tier-severe', bg: 'bg-tier-severe', soft: 'bg-purple-50 text-purple-700 border-purple-200' };
}

/**
 * Maps a 0-100 value to a color along the SAME severity ramp as the risk tiers,
 * blending smoothly between the locked tier colors.
 *
 * Used for factor bars and insight bars so a "high" reading looks high everywhere
 * with one consistent visual language (green → yellow → orange → red → purple),
 * rather than each component inventing its own palette. Interpolating between the
 * locked tier hexes keeps it on-brand while reading as a continuous gradient.
 *
 * @param value 0-100 (e.g. a percentile or normalized severity)
 * @returns an rgb() color string
 */
export function rampColor(value) {
  const v = Math.max(0, Math.min(100, Number(value) || 0));
  // Stops at the tier midpoints, using the locked tier hexes.
  const stops = [
    [0, [22, 163, 74]], // green
    [25, [234, 179, 8]], // yellow
    [50, [249, 115, 22]], // orange
    [75, [239, 68, 68]], // red
    [100, [168, 85, 247]], // purple
  ];
  let lo = stops[0];
  let hi = stops[stops.length - 1];
  for (let i = 0; i < stops.length - 1; i++) {
    if (v >= stops[i][0] && v <= stops[i + 1][0]) {
      lo = stops[i];
      hi = stops[i + 1];
      break;
    }
  }
  const span = hi[0] - lo[0] || 1;
  const t = (v - lo[0]) / span;
  const mix = (a, b) => Math.round(a + (b - a) * t);
  const [r, g, b] = [0, 1, 2].map((k) => mix(lo[1][k], hi[1][k]));
  return `rgb(${r}, ${g}, ${b})`;
}

/** Confidence pill styling, trust signal required alongside every correlation output. */
export function confidenceStyle(level) {
  switch ((level || '').toUpperCase()) {
    case 'HIGH':
      return { label: 'High confidence', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' };
    case 'MEDIUM':
      return { label: 'Medium confidence', cls: 'bg-yellow-50 text-yellow-700 border-yellow-200' };
    default:
      return { label: 'Low confidence', cls: 'bg-ink-100 text-ink-600 border-ink-200' };
  }
}

/** Human label for a severity 0-10, used in the log and history. */
export function severityLabel(sev) {
  if (sev == null) return 'n/a';
  if (sev === 0) return 'No symptoms';
  if (sev <= 3) return 'Mild';
  if (sev <= 6) return 'Moderate';
  if (sev <= 8) return 'Strong';
  return 'Severe';
}

export function severityColor(sev) {
  if (sev == null || sev === 0) return 'bg-ink-200 text-ink-600';
  if (sev <= 3) return 'bg-emerald-100 text-emerald-700';
  if (sev <= 6) return 'bg-yellow-100 text-yellow-700';
  if (sev <= 8) return 'bg-orange-100 text-orange-700';
  return 'bg-red-100 text-red-700';
}

const MED_TYPE_LABELS = {
  INHALER: 'Inhaler',
  ANTIHISTAMINE: 'Antihistamine',
  EPINEPHRINE: 'Epinephrine',
  NASAL_SPRAY: 'Nasal spray',
  EYE_DROPS: 'Eye drops',
  OTHER: 'Other',
};
export const MED_TYPES = Object.keys(MED_TYPE_LABELS);
export const medTypeLabel = (t) => MED_TYPE_LABELS[t] || t;

/** Short factor units for the conditions readout. */
export const FACTOR_UNITS = {
  pm25: 'µg/m³', pm10: 'µg/m³', o3: 'µg/m³', no2: 'µg/m³', so2: 'µg/m³', co: 'µg/m³',
  temperatureC: '°C', humidityPct: '%', pressureMslHpa: 'hPa', windSpeedMs: 'm/s',
};

export function formatDate(iso, opts) {
  if (!iso) return 'n/a';
  try {
    return new Date(iso).toLocaleDateString(undefined, opts || { month: 'short', day: 'numeric', year: 'numeric' });
  } catch {
    return iso;
  }
}

export function formatDateTime(iso) {
  if (!iso) return 'n/a';
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

export function formatRelative(iso) {
  if (!iso) return '';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs} hr ago`;
  const days = Math.round(hrs / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

/** ISO string for `n` days before now, used to seed history/export date ranges. */
export function daysAgoIso(n) {
  return new Date(Date.now() - n * 86400000).toISOString();
}

export const num = (v, digits = 1) =>
  v == null || Number.isNaN(Number(v)) ? 'n/a' : Number(v).toFixed(digits);

export const ATTRIBUTION = 'Weather and air quality data by Open-Meteo.com (ECMWF/CAMS)';
