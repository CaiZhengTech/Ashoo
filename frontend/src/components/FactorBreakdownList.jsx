import { useState } from 'react';
import { rampColor, num } from '../lib/format';

/** Turns a 0-100 personal percentile into a plain phrase, so the bar's meaning is
 *  obvious without knowing what a "percentile" is. */
function percentileWord(p) {
  if (p >= 90) return 'very high for you';
  if (p >= 70) return 'high for you';
  if (p >= 45) return 'about average for you';
  if (p >= 25) return 'low for you';
  return 'very low for you';
}

/**
 * Shows what's driving today's risk: for each factor, its REAL current reading
 * (with units) plus how that compares to the person's own history.
 *
 * The bar length and color follow the personal percentile (how high this is for
 * YOU, 0-100), using the shared severity ramp. The number on the right is the
 * actual measured value with its unit (e.g. "18.4 °C"), so it can never be misread
 * as "99 degrees" the way a bare percentile could. Rows are ordered by how much the
 * factor actually contributes today (its weight times how elevated it is).
 *
 * @param factors array of { key, displayName, percentile, aboveThreshold, weight, value, unit }
 * @param collapsible collapse to topN rows with a toggle
 * @param topN rows to show when collapsed
 */
export default function FactorBreakdownList({ factors = [], collapsible = true, topN = 6 }) {
  const [expanded, setExpanded] = useState(false);
  const sorted = [...factors].sort(
    (a, b) => (b.weight || 0) * (b.percentile || 0) - (a.weight || 0) * (a.percentile || 0)
  );
  const shown = collapsible && !expanded ? sorted.slice(0, topN) : sorted;
  const hiddenCount = sorted.length - shown.length;

  return (
    <div>
      <ul className="space-y-3.5">
        {shown.map((f) => {
          const pct = Math.max(0, Math.min(100, Math.round(f.percentile || 0)));
          const color = rampColor(pct);
          const hasValue = f.value != null;
          return (
            <li key={f.key}>
              <div className="mb-1 flex items-baseline justify-between gap-2">
                <div className="flex items-center gap-1.5">
                  <span className="text-sm font-medium text-ink-700">{f.displayName}</span>
                  {f.aboveThreshold && (
                    <span
                      className="h-1.5 w-1.5 rounded-full"
                      style={{ backgroundColor: color }}
                      title="Above the level that has preceded your symptoms"
                    />
                  )}
                </div>
                <div className="text-right">
                  {hasValue ? (
                    <span className="text-sm font-semibold tabular-nums text-ink-800">
                      {num(f.value, 1)} <span className="text-xs font-normal text-ink-400">{f.unit}</span>
                    </span>
                  ) : (
                    <span className="text-xs text-ink-400">no reading</span>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-ink-100">
                  <div
                    className="h-full rounded-full motion-safe:transition-[width] motion-safe:duration-500 motion-safe:ease-out"
                    style={{ width: `${pct}%`, backgroundColor: color }}
                  />
                </div>
                <span className="w-32 shrink-0 text-right text-[11px] text-ink-500">
                  {percentileWord(pct)}
                </span>
              </div>
            </li>
          );
        })}
      </ul>

      {collapsible && hiddenCount > 0 && (
        <button
          onClick={() => setExpanded(true)}
          className="mt-3 text-xs font-semibold text-brand-700 hover:text-brand-800"
        >
          Show {hiddenCount} more factor{hiddenCount === 1 ? '' : 's'}
        </button>
      )}
      {collapsible && expanded && (
        <button
          onClick={() => setExpanded(false)}
          className="mt-3 text-xs font-semibold text-brand-700 hover:text-brand-800"
        >
          Show fewer
        </button>
      )}

      <p className="mt-3 border-t border-ink-100 pt-2 text-[11px] leading-relaxed text-ink-400">
        The number on the right is today's actual reading. The colored bar shows how that compares
        with your own history (greener is calmer, redder is more like your past flare-ups). A dot
        marks factors above the level that has preceded your symptoms.
      </p>
    </div>
  );
}
