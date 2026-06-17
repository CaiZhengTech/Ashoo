import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ReferenceLine,
  ReferenceArea,
  CartesianGrid,
} from 'recharts';
import { formatDate, riskTier } from '../lib/format';

/**
 * Personal Risk Index over time as a filled area chart.
 *
 * A time series is the most natural way to show "how have my conditions trended,"
 * and the filled area reads as a continuous exposure band. We pin the Y axis to
 * the full 0-100 PRI range (not auto-scaled) so the same height always means the
 * same risk, and draw faint reference lines at the alert-on (70) and alert-off
 * (55) hysteresis thresholds so spikes are interpretable.
 *
 * @param data  array of { scoredAt, score } from /risk/history
 */
export default function RiskOverTimeChart({ data = [] }) {
  const points = data
    .filter((d) => d.score != null)
    .map((d) => ({ t: d.scoredAt, score: Math.round(d.score) }));

  const latest = points.length ? points[points.length - 1].score : 0;
  const tier = riskTier(latest);

  // Short spans (a day or two) read better with times than repeated dates.
  const spanMs =
    points.length > 1 ? new Date(points[points.length - 1].t) - new Date(points[0].t) : 0;
  const shortSpan = spanMs > 0 && spanMs <= 2 * 86400000;
  const xFmt = (t) =>
    shortSpan
      ? new Date(t).toLocaleTimeString(undefined, { hour: 'numeric' })
      : formatDate(t, { month: 'short', day: 'numeric' });

  const Dot = () => null; // keep the line clean; tooltip carries detail

  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={points} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
          <defs>
            <linearGradient id="riskFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={tier.hex} stopOpacity={0.35} />
              <stop offset="100%" stopColor={tier.hex} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          {/* Faint severity bands so low / moderate / high reads at a glance, using
              the locked tier colors. */}
          <ReferenceArea y1={0} y2={20} fill="#16a34a" fillOpacity={0.05} />
          <ReferenceArea y1={20} y2={40} fill="#eab308" fillOpacity={0.06} />
          <ReferenceArea y1={40} y2={60} fill="#f97316" fillOpacity={0.06} />
          <ReferenceArea y1={60} y2={80} fill="#ef4444" fillOpacity={0.06} />
          <ReferenceArea y1={80} y2={100} fill="#a855f7" fillOpacity={0.07} />
          <CartesianGrid strokeDasharray="3 3" stroke="#eef1f3" vertical={false} />
          <XAxis
            dataKey="t"
            tickFormatter={xFmt}
            tick={{ fontSize: 11, fill: '#6b7884' }}
            tickLine={false}
            axisLine={{ stroke: '#e3e8ec' }}
            minTickGap={28}
          />
          <YAxis
            domain={[0, 100]}
            ticks={[0, 20, 40, 60, 80, 100]}
            tick={{ fontSize: 11, fill: '#9aa6af' }}
            tickLine={false}
            axisLine={false}
            width={36}
          />
          <ReferenceLine y={70} stroke="#ef4444" strokeDasharray="4 4" strokeOpacity={0.5} />
          <ReferenceLine y={55} stroke="#f97316" strokeDasharray="4 4" strokeOpacity={0.35} />
          <Tooltip
            contentStyle={{
              borderRadius: 12,
              border: '1px solid #e3e8ec',
              boxShadow: '0 8px 24px -12px rgba(16,24,40,0.2)',
              fontSize: 12,
            }}
            labelFormatter={(t) => formatDate(t, { weekday: 'short', month: 'short', day: 'numeric' })}
            formatter={(v) => [`${v} PRI`, 'Risk']}
          />
          <Area
            type="monotone"
            dataKey="score"
            stroke={tier.hex}
            strokeWidth={2.5}
            fill="url(#riskFill)"
            dot={<Dot />}
            activeDot={{ r: 4, fill: tier.hex }}
            isAnimationActive
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
