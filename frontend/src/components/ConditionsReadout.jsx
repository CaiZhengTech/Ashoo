import { num, rampColor } from '../lib/format';

/**
 * Compact readout of a single environmental snapshot, with a gauge bar next to each
 * metric so severity is readable at a glance.
 *
 * Air-quality and pollen bars are scaled against rough "concern" ceilings and colored
 * on the shared severity ramp (green calm to red/purple severe), so a glance tells you
 * whether a number is benign or worrying without knowing the units. Weather isn't
 * "severe", so its bars just show where the value sits within a typical range, in a
 * neutral brand color. Nullable metrics (pollen is Europe-only) are skipped entirely.
 */

// Rough upper bounds (µg/m³) where a pollutant is clearly unhealthy; used only to
// scale the visual bar, not as official AQI.
const AIR_MAX = { pm25: 55, pm10: 150, o3: 180, no2: 200, so2: 350, co: 4000 };
const POLLEN_MAX = 80; // grains/m³ that reads as "high" for most pollens
const WEATHER_BRAND = '#22a7a6';

function airScale(key) {
  return (v) => {
    const pct = Math.max(0, Math.min(100, (v / AIR_MAX[key]) * 100));
    return { pct, color: rampColor(pct) };
  };
}
function pollenScale(v) {
  const pct = Math.max(0, Math.min(100, (v / POLLEN_MAX) * 100));
  return { pct, color: rampColor(pct) };
}
function weatherScale(kind) {
  return (v) => {
    let pct;
    switch (kind) {
      case 'temp': pct = ((v + 10) / 50) * 100; break; // -10..40 C
      case 'humidity': pct = v; break; // 0..100 %
      case 'pressure': pct = ((v - 980) / 60) * 100; break; // 980..1040 hPa
      default: pct = (v / 25) * 100; break; // wind 0..25 m/s
    }
    return { pct: Math.max(0, Math.min(100, pct)), color: WEATHER_BRAND };
  };
}

function Metric({ label, value, unit, scale }) {
  if (value == null) return null;
  const bar = scale ? scale(value) : null;
  return (
    <div className="rounded-lg bg-ink-50 px-3 py-2">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-ink-400">{label}</div>
      <div className="text-sm font-bold tabular-nums text-ink-800">
        {num(value, 1)}
        {unit && <span className="ml-0.5 text-[10px] font-medium text-ink-400">{unit}</span>}
      </div>
      {bar && (
        <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-ink-200/70">
          <div
            className="h-full rounded-full motion-safe:transition-[width] motion-safe:duration-500"
            style={{ width: `${bar.pct}%`, backgroundColor: bar.color }}
          />
        </div>
      )}
    </div>
  );
}

export default function ConditionsReadout({ snapshot }) {
  if (!snapshot) return null;
  const s = snapshot;
  const hasPollen = [s.pollenGrass, s.pollenBirch, s.pollenRagweed, s.pollenAlder].some(
    (v) => v != null
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-ink-800">{s.cityName}</div>
          {s.aqiComputed != null && (
            <div className="text-xs text-ink-500">EPA AQI {s.aqiComputed}</div>
          )}
        </div>
        {s.dataOrigin === 'SEEDED_SYNTHETIC' && (
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold text-amber-700">
            synthetic
          </span>
        )}
      </div>

      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-400">
          Air quality
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Metric label="PM2.5" value={s.pm25} unit="µg/m³" scale={airScale('pm25')} />
          <Metric label="PM10" value={s.pm10} unit="µg/m³" scale={airScale('pm10')} />
          <Metric label="Ozone" value={s.o3} unit="µg/m³" scale={airScale('o3')} />
          <Metric label="NO₂" value={s.no2} unit="µg/m³" scale={airScale('no2')} />
          <Metric label="SO₂" value={s.so2} unit="µg/m³" scale={airScale('so2')} />
          <Metric label="CO" value={s.co} unit="µg/m³" scale={airScale('co')} />
        </div>
      </div>

      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-400">
          Weather
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Metric label="Temp" value={s.temperatureC} unit="°C" scale={weatherScale('temp')} />
          <Metric label="Humidity" value={s.humidityPct} unit="%" scale={weatherScale('humidity')} />
          <Metric label="Pressure" value={s.pressureMslHpa} unit="hPa" scale={weatherScale('pressure')} />
          <Metric label="Wind" value={s.windSpeedMs} unit="m/s" scale={weatherScale('wind')} />
          <Metric label="Gusts" value={s.windGustsMs} unit="m/s" scale={weatherScale('wind')} />
        </div>
      </div>

      {hasPollen ? (
        <div>
          <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-400">
            Pollen (grains/m³)
          </div>
          <div className="grid grid-cols-3 gap-2">
            <Metric label="Grass" value={s.pollenGrass} unit="" scale={pollenScale} />
            <Metric label="Birch" value={s.pollenBirch} unit="" scale={pollenScale} />
            <Metric label="Ragweed" value={s.pollenRagweed} unit="" scale={pollenScale} />
            <Metric label="Alder" value={s.pollenAlder} unit="" scale={pollenScale} />
            <Metric label="Mugwort" value={s.pollenMugwort} unit="" scale={pollenScale} />
            <Metric label="Olive" value={s.pollenOlive} unit="" scale={pollenScale} />
          </div>
        </div>
      ) : (
        <p className="rounded-lg bg-ink-50 px-3 py-2 text-xs text-ink-500">
          Pollen data is Europe-only (Open-Meteo/CAMS). US locations show air quality and weather.
        </p>
      )}

      <p className="border-t border-ink-100 pt-2 text-[11px] leading-relaxed text-ink-400">
        Air and pollen bars run green (clean) to red (unhealthy). Weather bars show where the
        reading sits within a typical range.
      </p>
    </div>
  );
}
