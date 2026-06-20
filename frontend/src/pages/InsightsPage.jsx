import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getCorrelationResults,
  getMismatches,
  computeCorrelation,
} from '../api/endpoints';
import { errorMessage } from '../api/client';
import {
  Card,
  Button,
  Pill,
  Skeleton,
  EmptyState,
  ErrorState,
  SectionTitle,
} from '../components/ui';
import { confidenceStyle, num, formatDate, rampColor } from '../lib/format';
import { usePersona } from '../lib/PersonaContext';

const LAG_LABEL = { 0: 'same day', 24: '1 day before', 48: '2 days before', 72: '3 days before' };

function strengthLabel(rho) {
  const s = Math.abs(rho ?? 0);
  if (s >= 0.5) return 'Strong';
  if (s >= 0.3) return 'Moderate';
  if (s >= 0.15) return 'Mild';
  return 'Weak';
}

/**
 * One learned relationship as a compact, expandable row.
 *
 * Twelve full stat-cards overwhelm the page, so the default view is a scannable
 * row: factor, when it links, a strength bar, and confidence. The raw stats
 * (threshold, day counts, mismatches) live behind a click for anyone who wants
 * them, keeping the surface calm without hiding the honesty.
 */
function FactorRow({ r }) {
  const [open, setOpen] = useState(false);
  const conf = confidenceStyle(r.confidenceLevel);
  const strength = Math.min(100, Math.abs(r.spearmanR ?? 0) * 100);
  const positive = (r.spearmanR ?? 0) >= 0;
  // Stronger links read warmer on the shared severity ramp; protective (inverse)
  // links get a calm blue so direction is obvious at a glance.
  const barColor = positive ? rampColor(Math.max(20, strength * 1.6)) : '#818cf8';

  return (
    <li className="py-2.5">
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-3 text-left"
      >
        <div className="w-32 shrink-0 sm:w-40">
          <div className="truncate text-sm font-semibold text-ink-800">{r.displayName}</div>
          <div className="text-[11px] text-ink-500">
            links {LAG_LABEL[r.bestLagHours] ?? `${r.bestLagHours}h before`}
          </div>
        </div>

        <div className="flex-1">
          <div className="h-2 w-full overflow-hidden rounded-full bg-ink-100">
            <div
              className="h-full rounded-full motion-safe:transition-[width] motion-safe:duration-500 motion-safe:ease-out"
              style={{ width: `${Math.max(6, strength)}%`, backgroundColor: barColor }}
            />
          </div>
        </div>

        <span className="hidden w-16 shrink-0 text-right text-xs font-medium text-ink-500 sm:inline">
          {strengthLabel(r.spearmanR)}
        </span>
        <Pill className={`${conf.cls} shrink-0`}>{conf.label.replace(' confidence', '')}</Pill>
        <span className="w-4 shrink-0 text-ink-400">{open ? '▴' : '▾'}</span>
      </button>

      {open && (
        <div className="mt-2 space-y-1.5 rounded-lg bg-ink-50/70 p-3 text-xs text-ink-600 sm:ml-[8.5rem]">
          <Detail
            label="How strong the link is"
            value={`${strengthLabel(r.spearmanR)} ${positive ? '(raises your risk)' : '(tends to calm it)'}`}
            hint="How reliably this factor moves together with your symptom days."
          />
          <Detail
            label="When it matters most"
            value={LAG_LABEL[r.bestLagHours] ?? `${r.bestLagHours}h before`}
            hint="Whether symptoms tend to follow the same day or a day or two later."
          />
          <Detail
            label="Level to watch"
            value={num(r.personalThreshold, 1)}
            hint="Readings above this have more often preceded your symptoms."
          />
          <Detail
            label="Share of your risk score"
            value={`${num((r.weight ?? 0) * 100, 0)}%`}
            hint="How much this one factor counts toward your overall index."
          />
          <Detail
            label="Learned from"
            value={`${r.symptomDaysUsed} of ${r.totalDaysUsed} days`}
            hint="More logged days means a more trustworthy pattern."
          />
        </div>
      )}
    </li>
  );
}

function Detail({ label, value, hint }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:gap-1.5">
      <span className="w-40 shrink-0 font-semibold text-ink-700">{label}</span>
      <span className="font-semibold tabular-nums text-ink-800">{value}</span>
      {hint && <span className="text-ink-400">, {hint}</span>}
    </div>
  );
}

/** The ranked list of learned relationships, strongest-weighted first, with the
 *  long tail of low-weight factors tucked behind a toggle. */
function FactorList({ results }) {
  const [showAll, setShowAll] = useState(false);
  const sorted = [...results].sort((a, b) => (b.weight ?? 0) - (a.weight ?? 0));
  const TOP = 6;
  const shown = showAll ? sorted : sorted.slice(0, TOP);
  const hidden = sorted.length - shown.length;

  return (
    <>
      <ul className="divide-y divide-ink-100">
        {shown.map((r) => (
          <FactorRow key={r.factorName} r={r} />
        ))}
      </ul>
      {hidden > 0 && (
        <button
          onClick={() => setShowAll(true)}
          className="mt-2 text-xs font-semibold text-brand-700 hover:text-brand-800"
        >
          Show {hidden} more factor{hidden === 1 ? '' : 's'}
        </button>
      )}
      {showAll && sorted.length > TOP && (
        <button
          onClick={() => setShowAll(false)}
          className="mt-2 text-xs font-semibold text-brand-700 hover:text-brand-800"
        >
          Show fewer
        </button>
      )}
      <p className="mt-3 border-t border-ink-100 pt-2 text-[11px] leading-relaxed text-ink-400">
        Tap any factor for a plain-language breakdown. Bar length shows how strongly it links to
        symptoms, warmer = stronger trigger; <span className="text-indigo-400">blue</span> means
        it tends to <em>calm</em> things (an inverse link).
      </p>
    </>
  );
}

function MismatchRow({ m }) {
  const isHigh = m.type === 'HIGH_SCORE_NO_SYMPTOMS';
  return (
    <li className="flex items-start gap-3 py-3">
      <span
        className={`mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-lg text-xs font-bold ${
          isHigh ? 'bg-purple-100 text-purple-700' : 'bg-sky-100 text-sky-700'
        }`}
      >
        {isHigh ? '?' : '!'}
      </span>
      <div>
        <div className="text-sm font-medium text-ink-800">{formatDate(m.date)}</div>
        <p className="text-sm text-ink-600">{m.explanation}</p>
      </div>
    </li>
  );
}

export default function InsightsPage() {
  const qc = useQueryClient();
  const { userParam, persona, meta } = usePersona();
  const who = persona === 'you' ? 'your' : `${meta.name}'s`;
  const results = useQuery({
    queryKey: ['correlation', 'results', userParam ?? 'you'],
    queryFn: () => getCorrelationResults(userParam),
    retry: 0,
  });
  const mismatches = useQuery({
    queryKey: ['correlation', 'mismatches', userParam ?? 'you'],
    queryFn: () => getMismatches(userParam),
    retry: 0,
  });

  const recompute = useMutation({
    mutationFn: () => computeCorrelation(userParam),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['correlation'] });
      qc.invalidateQueries({ queryKey: ['risk'] });
      qc.invalidateQueries({ queryKey: ['briefing'] });
    },
  });

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-ink-800">
            {persona === 'you' ? 'Your' : `${meta.name}'s`} trigger fingerprint
          </h1>
          <p className="mt-1 max-w-2xl text-sm text-ink-500">
            Ashoo correlates {who} logged symptom days against archived conditions, honest,
            transparent statistics, not a black box. These are patterns, never proof of cause.
          </p>
        </div>
        <Button
          variant="secondary"
          onClick={() => recompute.mutate()}
          disabled={recompute.isPending}
        >
          {recompute.isPending ? 'Recomputing…' : '↻ Recompute'}
        </Button>
      </div>

      <Card className="card-pad">
        <SectionTitle eyebrow="Learned relationships" title="What links to your symptoms" />
        {results.isLoading ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <Skeleton className="h-40 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : results.isError ? (
          <ErrorState message={errorMessage(results.error)} onRetry={() => results.refetch()} />
        ) : !results.data?.length ? (
          <EmptyState
            icon="🔬"
            title="No correlations computed yet"
            hint="Log symptom days (or load demo data), then press Recompute. Ashoo needs at least a handful of days to find patterns."
            action={
              <Button onClick={() => recompute.mutate()} disabled={recompute.isPending}>
                Compute now
              </Button>
            }
          />
        ) : (
          <FactorList results={results.data} />
        )}
      </Card>

      <Card className="card-pad">
        <SectionTitle
          eyebrow="Honesty check"
          title="Days the model disagreed with you"
        />
        <p className="-mt-1 mb-3 text-sm text-ink-500">
          We show where the score and how you actually felt diverged. No model is perfect ,
          surfacing misses is part of earning your trust.
        </p>
        {mismatches.isLoading ? (
          <Skeleton className="h-24 w-full" />
        ) : mismatches.isError ? (
          <ErrorState message={errorMessage(mismatches.error)} onRetry={() => mismatches.refetch()} />
        ) : !mismatches.data?.length ? (
          <EmptyState
            icon="✅"
            title="No notable mismatches"
            hint="Either your model is tracking well, or there aren't enough logged days yet to flag divergences."
          />
        ) : (
          <ul className="divide-y divide-ink-100">
            {mismatches.data.map((m, i) => (
              <MismatchRow key={`${m.date}-${i}`} m={m} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
