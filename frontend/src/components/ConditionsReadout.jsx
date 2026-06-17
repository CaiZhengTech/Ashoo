import { num } from '../lib/format';

/**
 * Compact readout of a single environmental snapshot.
 *
 * Conditions come back with many nullable fields (pollen is Europe-only, some
 * gases may be absent), so we render only the metrics that are present rather
 * than a grid full of dashes. Grouping by Air / Weather / Pollen matches how a
 * user thinks about "what's in the air right now."
 */
function Metric({ label, value, unit }) {
  if (value == null) return null;
  return (
    <div className="rounded-lg bg-ink-50 px-3 py-2">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-ink-400">{label}</div>
      <div className="text-sm font-bold tabular-nums text-ink-800">
        {num(value, 1)}
        <span className="ml-0.5 text-[10px] font-medium text-ink-400">{unit}</span>
      </div>
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
          <Metric label="PM2.5" value={s.pm25} unit="µg/m³" />
          <Metric label="PM10" value={s.pm10} unit="µg/m³" />
          <Metric label="Ozone" value={s.o3} unit="µg/m³" />
          <Metric label="NO₂" value={s.no2} unit="µg/m³" />
          <Metric label="SO₂" value={s.so2} unit="µg/m³" />
          <Metric label="CO" value={s.co} unit="µg/m³" />
        </div>
      </div>

      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-400">
          Weather
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Metric label="Temp" value={s.temperatureC} unit="°C" />
          <Metric label="Humidity" value={s.humidityPct} unit="%" />
          <Metric label="Pressure" value={s.pressureMslHpa} unit="hPa" />
          <Metric label="Wind" value={s.windSpeedMs} unit="m/s" />
          <Metric label="Gusts" value={s.windGustsMs} unit="m/s" />
        </div>
      </div>

      {hasPollen ? (
        <div>
          <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-400">
            Pollen (grains/m³)
          </div>
          <div className="grid grid-cols-3 gap-2">
            <Metric label="Grass" value={s.pollenGrass} unit="" />
            <Metric label="Birch" value={s.pollenBirch} unit="" />
            <Metric label="Ragweed" value={s.pollenRagweed} unit="" />
            <Metric label="Alder" value={s.pollenAlder} unit="" />
            <Metric label="Mugwort" value={s.pollenMugwort} unit="" />
            <Metric label="Olive" value={s.pollenOlive} unit="" />
          </div>
        </div>
      ) : (
        <p className="rounded-lg bg-ink-50 px-3 py-2 text-xs text-ink-500">
          Pollen data is Europe-only (Open-Meteo/CAMS). US locations show air quality and weather.
        </p>
      )}
    </div>
  );
}
