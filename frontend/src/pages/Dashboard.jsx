import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getRiskCurrent, getRiskHistory } from '../api/endpoints';
import { riskTier, confidenceStyle, formatRelative } from '../lib/format';
import { errorMessage } from '../api/client';
import { usePersona } from '../lib/PersonaContext';
import { Card, Pill, Skeleton, ErrorState, EmptyState, Button } from '../components/ui';
import RiskBadge from '../components/RiskBadge';
import FactorBreakdownList from '../components/FactorBreakdownList';
import DailyBriefingCard from '../components/DailyBriefingCard';
import RiskOverTimeChart from '../components/RiskOverTimeChart';
import CurrentReminders from '../components/CurrentReminders';
import DemoExplorer from '../components/DemoExplorer';

const RANGES = [
  { key: 'today', label: 'Today', days: 1 },
  { key: '7d', label: '7 days', days: 7 },
  { key: '30d', label: '30 days', days: 30 },
  { key: '3mo', label: '3 months', days: 90 },
  { key: '6mo', label: '6 months', days: 180 },
];

function HeroSkeleton() {
  return (
    <Card className="card-pad">
      <div className="flex flex-col items-center gap-6 sm:flex-row sm:items-center sm:gap-8">
        <Skeleton className="h-48 w-48 rounded-full" />
        <div className="flex-1 space-y-3">
          <Skeleton className="h-6 w-40" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </div>
      </div>
    </Card>
  );
}

function NotSetUp() {
  return (
    <Card className="card-pad">
      <EmptyState
        icon="🌱"
        title="No personal risk model yet"
        hint="Ashoo learns from logged symptom days. Seed the demo data above, or log a few days of how you felt, then recompute, your personalized risk index appears here."
        action={
          <Link to="/log">
            <Button>Log how you feel</Button>
          </Link>
        }
      />
    </Card>
  );
}

function RiskHero({ data }) {
  const tier = riskTier(data.score);
  const conf = confidenceStyle(data.confidenceLevel);
  return (
    <Card className="card-pad animate-fade-in-up">
      <div className="flex flex-col items-center gap-6 sm:flex-row sm:items-center sm:gap-8">
        <RiskBadge score={data.score} />
        <div className="flex-1 text-center sm:text-left">
          <div className="mb-2 flex flex-wrap items-center justify-center gap-2 sm:justify-start">
            <span className="label-eyebrow">Personal Risk Index</span>
            <Pill className={conf.cls}>{conf.label}</Pill>
            {data.alertActive && (
              <Pill className="border-red-200 bg-red-50 text-red-700">Alert active</Pill>
            )}
          </div>
          <h1 className="text-2xl font-bold text-ink-800">
            Conditions look <span style={{ color: tier.hex }}>{tier.label.toLowerCase()}</span> today
          </h1>
          <p className="mt-2 max-w-prose text-[15px] leading-relaxed text-ink-600">
            {data.guidance}
          </p>
          <p className="mt-3 text-xs text-ink-500">
            Based on {data.symptomDaysAvailable} logged symptom day
            {data.symptomDaysAvailable === 1 ? '' : 's'} · scored {formatRelative(data.scoredAt)} ·{' '}
            {data.confidenceMessage}
          </p>
        </div>
      </div>
    </Card>
  );
}

function TrendCard() {
  const { userParam } = usePersona();
  const [range, setRange] = useState('30d');
  const active = RANGES.find((r) => r.key === range) || RANGES[2];
  const fromIso = new Date(Date.now() - active.days * 86400000).toISOString();
  const toIso = new Date().toISOString();

  const history = useQuery({
    queryKey: ['risk', 'history', userParam ?? 'you', range],
    queryFn: () => getRiskHistory(fromIso, toIso, userParam),
    retry: 0,
  });

  const hasData = history.data?.some((d) => d.score != null);

  return (
    <Card className="card-pad">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-semibold text-ink-800">Risk over time</h2>
        <div className="flex flex-wrap gap-1 rounded-lg bg-ink-100/70 p-0.5">
          {RANGES.map((r) => (
            <button
              key={r.key}
              onClick={() => setRange(r.key)}
              className={`rounded-md px-2.5 py-1 text-xs font-semibold transition-colors ${
                range === r.key
                  ? 'bg-white text-brand-700 shadow-sm'
                  : 'text-ink-500 hover:text-ink-700'
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>
      {history.isLoading ? (
        <Skeleton className="h-56 w-full" />
      ) : hasData ? (
        <>
          <RiskOverTimeChart data={history.data} />
          <p className="mt-1 text-center text-[11px] text-ink-400">
            Colored bands show severity tiers (green calm, up to purple severe). Dashed lines mark
            the alert-on (70) and alert-off (55) thresholds.
          </p>
        </>
      ) : (
        <EmptyState
          icon="📈"
          title="No score history in this range"
          hint="Seed demo data or log a few days, then recompute to build your trend."
        />
      )}
    </Card>
  );
}

export default function Dashboard() {
  const { userParam, persona } = usePersona();
  const risk = useQuery({
    queryKey: ['risk', 'current', userParam ?? 'you'],
    queryFn: () => getRiskCurrent(userParam),
    retry: 0,
  });

  const notSetUp = risk.isError && risk.error?.response?.status === 409;

  return (
    <div className="space-y-6">
      <DemoExplorer />

      {/* Hero + reminders grouped together */}
      {risk.isLoading ? (
        <HeroSkeleton />
      ) : notSetUp ? (
        <NotSetUp />
      ) : risk.isError ? (
        <Card className="card-pad">
          <ErrorState message={errorMessage(risk.error)} onRetry={() => risk.refetch()} />
        </Card>
      ) : (
        <div className="space-y-3">
          <RiskHero data={risk.data} />
          <CurrentReminders />
        </div>
      )}

      {/* Two independent column stacks so cards pack tightly with no cross-row white
          space: the briefing sits directly above the trend chart on the left, and the
          factor breakdown above reminders on the right. */}
      <div className="grid items-start gap-6 lg:grid-cols-5">
        <div className="flex flex-col gap-6 lg:col-span-3">
          <DailyBriefingCard />
          <TrendCard />
        </div>
        <div className="flex flex-col gap-6 lg:col-span-2">
          <Card className="card-pad">
            <h2 className="mb-1 text-base font-semibold text-ink-800">What's driving it</h2>
            <p className="mb-4 text-xs text-ink-500">
              Each factor vs. {persona === 'you' ? 'your' : 'their'} own history, most influential
              first.
            </p>
            {risk.data?.factors?.length ? (
              <FactorBreakdownList factors={risk.data.factors} />
            ) : (
              <p className="text-sm text-ink-500">
                Factor breakdown appears once the model is computed.
              </p>
            )}
          </Card>
        </div>
      </div>

      {risk.data?.attribution && (
        <p className="text-center text-xs text-ink-400">{risk.data.attribution}</p>
      )}
    </div>
  );
}
