import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getCurrentReminders, getReminderRules } from '../api/endpoints';
import { Card, Skeleton, EmptyState, ErrorState } from './ui';
import { errorMessage } from '../api/client';
import { usePersona } from '../lib/PersonaContext';

/**
 * Shows reminders that match current conditions, each one the user's OWN note
 * echoed back, never advice Ashoo invented.
 *
 * The mandatory disclaimer travels with every reminder from the backend and is
 * rendered verbatim and un-dismissable, satisfying the reminder safety contract.
 * A reminder only "fires" when conditions cross the threshold the user set, so we
 * also look at their configured rules: that way adding a reminder visibly changes
 * this card (it shows "set, not currently triggered") instead of looking like
 * nothing happened when today's risk is simply below the threshold.
 */
export default function CurrentReminders() {
  const { userParam } = usePersona();
  const fired = useQuery({
    queryKey: ['reminders', 'current', userParam ?? 'you'],
    queryFn: () => getCurrentReminders(userParam),
    retry: 0,
  });
  const rules = useQuery({
    queryKey: ['reminder-rules', userParam ?? 'you'],
    queryFn: () => getReminderRules(userParam),
    retry: 0,
  });

  return (
    <Card className="card-pad">
      <h2 className="mb-3 text-base font-semibold text-ink-800">Your reminders for now</h2>

      {fired.isLoading ? (
        <Skeleton className="h-16 w-full" />
      ) : fired.isError ? (
        <ErrorState message={errorMessage(fired.error)} onRetry={() => fired.refetch()} />
      ) : fired.data?.length ? (
        <ul className="space-y-3">
          {fired.data.map((r) => (
            <li key={r.ruleId} className="rounded-xl border border-orange-200 bg-orange-50/60 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="font-medium text-ink-800">{r.userNote}</p>
                  <p className="mt-0.5 text-xs text-ink-500">
                    {r.medicationName ? `${r.medicationName} · ${r.medicationType} · ` : ''}
                    triggers at PRI ≥ {Math.round(r.threshold)}
                  </p>
                </div>
              </div>
              <p className="mt-3 border-t border-orange-200/70 pt-2 text-xs leading-relaxed text-orange-800">
                {r.disclaimer}
              </p>
            </li>
          ))}
        </ul>
      ) : rules.data?.length ? (
        // Rules exist but none are triggered by the current conditions.
        <div className="rounded-xl border border-ink-200 bg-ink-50/60 p-4 text-sm text-ink-600">
          <p className="font-medium text-ink-700">Nothing triggered right now ✦</p>
          <p className="mt-1 text-xs leading-relaxed text-ink-500">
            You have {rules.data.length} reminder{rules.data.length === 1 ? '' : 's'} set. None
            match the current conditions right now. They'll appear here automatically when
            conditions cross the levels you chose.
          </p>
          <Link
            to="/reminders"
            className="mt-3 inline-block text-xs font-semibold text-brand-700 hover:text-brand-800"
          >
            Manage reminders
          </Link>
        </div>
      ) : (
        <EmptyState
          icon="🔔"
          title="No reminders set up yet"
          hint="Add a reminder and Ashoo will echo your note back when conditions cross a threshold you choose."
          action={
            <Link
              to="/reminders"
              className="rounded-xl bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700"
            >
              Set up reminders
            </Link>
          }
        />
      )}
    </Card>
  );
}
