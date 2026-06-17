import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getCurrentReminders } from '../api/endpoints';
import { Card, Skeleton, EmptyState, ErrorState } from './ui';
import { errorMessage } from '../api/client';

/**
 * Shows reminders that match current conditions, each one the user's OWN note
 * echoed back, never advice Ashoo invented.
 *
 * The mandatory disclaimer travels with every reminder from the backend and is
 * rendered verbatim and un-dismissable, satisfying the reminder safety contract.
 * If the user hasn't consented yet, the endpoint returns nothing and we point them
 * to the Care page rather than inventing a reminder.
 */
export default function CurrentReminders() {
  const query = useQuery({
    queryKey: ['reminders', 'current'],
    queryFn: getCurrentReminders,
    retry: 0,
  });

  return (
    <Card className="card-pad">
      <h2 className="mb-3 text-base font-semibold text-ink-800">Your reminders for now</h2>

      {query.isLoading ? (
        <Skeleton className="h-16 w-full" />
      ) : query.isError ? (
        <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
      ) : !query.data?.length ? (
        <EmptyState
          icon="🔔"
          title="No reminders match right now"
          hint="Reminders fire only when conditions cross thresholds you set. Add rules on the Care page."
          action={
            <Link
              to="/care"
              className="rounded-xl bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700"
            >
              Set up reminders
            </Link>
          }
        />
      ) : (
        <ul className="space-y-3">
          {query.data.map((r) => (
            <li
              key={r.ruleId}
              className="rounded-xl border border-orange-200 bg-orange-50/60 p-4"
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="font-medium text-ink-800">{r.userNote}</p>
                  <p className="mt-0.5 text-xs text-ink-500">
                    {r.medicationName} · {r.medicationType} · triggers at PRI ≥{' '}
                    {Math.round(r.threshold)}
                  </p>
                </div>
              </div>
              <p className="mt-3 border-t border-orange-200/70 pt-2 text-xs leading-relaxed text-orange-800">
                {r.disclaimer}
              </p>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
