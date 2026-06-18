import { riskTier } from '../lib/format';

/**
 * Circular gauge that is the visual anchor of the whole app, the Personal Risk
 * Index, 0-100, in its locked tier color.
 *
 * An SVG arc gauge (rather than a number alone) gives an instant, pre-literate
 * read of "where am I on the scale", the dial fills proportionally and the color
 * carries the tier meaning. We draw two stacked circles: a faint track and a
 * colored progress arc using stroke-dasharray, the standard trick for SVG gauges.
 *
 * @param score 0-100 smoothed PRI
 * @param size  pixel diameter
 */
export default function RiskBadge({ score = 0, size = 200, showLabel = true }) {
  const tier = riskTier(score);
  const stroke = 14;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const pct = Math.max(0, Math.min(100, Number(score) || 0)) / 100;
  const offset = c * (1 - pct);

  return (
    <div className="flex flex-col items-center">
      <div className="relative" style={{ width: size, height: size }}>
        <svg width={size} height={size} className="-rotate-90">
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke="#e3e8ec"
            strokeWidth={stroke}
          />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke={tier.hex}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={c}
            strokeDashoffset={offset}
            style={{ transition: 'stroke-dashoffset 0.55s cubic-bezier(0.22,1,0.36,1)' }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-5xl font-extrabold tabular-nums text-ink-800">
            {Math.round(Number(score) || 0)}
          </span>
          <span className="text-xs font-medium text-ink-500">/ 100 PRI</span>
        </div>
      </div>
      {showLabel && (
        <div
          className="mt-3 inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-bold text-white"
          style={{ backgroundColor: tier.hex }}
        >
          <span aria-hidden>{tier.emoji}</span>
          {tier.label}
        </div>
      )}
    </div>
  );
}
