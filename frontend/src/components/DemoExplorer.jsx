import { useMutation, useQueryClient } from '@tanstack/react-query';
import { seedDemo, computeCorrelation } from '../api/endpoints';
import { usePersona, PERSONAS } from '../lib/PersonaContext';
import { useToast } from '../lib/ToastContext';
import { errorMessage } from '../api/client';
import { Card, Button, InfoTip } from './ui';

/**
 * Front-and-center demo control on the dashboard.
 *
 * Recruiters can switch whose data they're viewing (the real user or a seeded
 * persona) and the whole dashboard re-queries for that person. "Seed / refresh"
 * regenerates all synthetic data and models; "Recompute" re-runs the model for
 * just the person currently in view. Putting this up top makes the personalized
 * engine immediately explorable without hunting through settings.
 */
export default function DemoExplorer() {
  const qc = useQueryClient();
  const toast = useToast();
  const { persona, setPersona, userParam } = usePersona();

  const seed = useMutation({
    mutationFn: async () => {
      // Seeding already computes every persona server-side; just refresh the views.
      return seedDemo();
    },
    onSuccess: () => {
      qc.invalidateQueries();
      toast('Demo data refreshed for all personas');
    },
  });

  const recompute = useMutation({
    mutationFn: () => computeCorrelation(userParam),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['risk'] });
      qc.invalidateQueries({ queryKey: ['correlation'] });
      qc.invalidateQueries({ queryKey: ['briefing'] });
      toast('Model recomputed');
    },
  });

  const busy = seed.isPending || recompute.isPending;

  return (
    <Card className="card-pad bg-gradient-to-br from-white to-brand-50/40">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="label-eyebrow mb-1">Explore the engine</div>
          <h2 className="text-base font-semibold text-ink-800">Whose data are you viewing?</h2>
          <p className="mt-0.5 text-xs text-ink-500">
            Switch between yourself and three seeded personas to see how the same engine adapts to
            different sensitivities.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1">
            <Button variant="secondary" onClick={() => recompute.mutate()} disabled={busy}>
              {recompute.isPending ? 'Recomputing…' : '↻ Recompute'}
            </Button>
            <InfoTip text="Re-runs the statistics for whoever you're viewing using their current logged days. Use it after adding or editing log entries to refresh the risk score, factors, and trend." />
          </div>
          <div className="flex items-center gap-1">
            <Button onClick={() => seed.mutate()} disabled={busy}>
              {seed.isPending ? 'Seeding…' : 'Seed / refresh demo'}
            </Button>
            <InfoTip text="Regenerates the three demo personas (Alex, Jordan, Morgan) with fresh synthetic data and rebuilds every model. Start here, then pick a persona to explore. It does not touch your own real entries." />
          </div>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {PERSONAS.map((p) => {
          const active = persona === p.key;
          return (
            <button
              key={p.key}
              onClick={() => setPersona(p.key)}
              aria-pressed={active}
              className={`rounded-xl border p-3 text-left transition-all ${
                active
                  ? 'border-brand-400 bg-brand-50 shadow-sm ring-1 ring-brand-200'
                  : 'border-ink-200 bg-white hover:border-brand-200 hover:bg-brand-50/40'
              }`}
            >
              <div className="flex items-center gap-1.5">
                <span
                  className={`h-2 w-2 rounded-full ${active ? 'bg-brand-500' : 'bg-ink-300'}`}
                />
                <span className="text-sm font-semibold text-ink-800">{p.name}</span>
              </div>
              <div className="mt-0.5 text-[11px] text-ink-500">{p.blurb}</div>
            </button>
          );
        })}
      </div>

      {(seed.isError || recompute.isError) && (
        <p className="mt-2 text-xs text-red-600">
          {errorMessage(seed.error || recompute.error)}
        </p>
      )}
      {seed.isSuccess && !busy && (
        <p className="mt-2 text-xs text-emerald-700">Demo data refreshed for all personas.</p>
      )}
    </Card>
  );
}
