import { useQuery } from '@tanstack/react-query';
import { getBriefing } from '../api/endpoints';
import { usePersona } from '../lib/PersonaContext';
import { Card, Pill, Skeleton, ErrorState } from './ui';
import { formatRelative } from '../lib/format';
import { errorMessage } from '../api/client';

const SOURCE_META = {
  claude: { label: 'AI briefing', cls: 'bg-brand-50 text-brand-700 border-brand-200' },
  cached: { label: 'AI briefing · cached', cls: 'bg-brand-50 text-brand-700 border-brand-200' },
  fallback: { label: 'Auto summary', cls: 'bg-ink-100 text-ink-600 border-ink-200' },
};

/**
 * The plain-English daily briefing, Ashoo's most human surface.
 *
 * It is advisory only and always ends with "consult your doctor" (enforced
 * server-side). We label the source honestly (Claude vs. deterministic fallback)
 * so the user knows whether the AI was reachable, and we never re-style the
 * doctor disclaimer away, it stays in the body text exactly as returned.
 */
export default function DailyBriefingCard() {
  const { isDemo, userParam } = usePersona();
  const query = useQuery({
    queryKey: ['briefing', userParam ?? 'you'],
    queryFn: () => getBriefing(isDemo, userParam),
  });

  const meta = SOURCE_META[query.data?.source] || SOURCE_META.fallback;

  return (
    <Card className="card-pad bg-gradient-to-br from-white to-brand-50/40">
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="grid h-7 w-7 place-items-center rounded-lg bg-brand-100 text-brand-700">
            ✦
          </span>
          <h2 className="text-base font-semibold text-ink-800">Today's briefing</h2>
        </div>
        {query.data && (
          <Pill className={meta.cls}>{meta.label}</Pill>
        )}
      </div>

      {query.isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-11/12" />
          <Skeleton className="h-4 w-4/5" />
        </div>
      ) : query.isError ? (
        <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
      ) : (
        <>
          <p className="whitespace-pre-line text-[15px] leading-relaxed text-ink-700">
            {query.data.text}
          </p>
          <div className="mt-3 flex items-center gap-2 text-xs text-ink-400">
            <span>Generated {formatRelative(query.data.generatedAt)}</span>
            {query.data.tokensUsed > 0 && (
              <>
                <span aria-hidden>·</span>
                <span>{query.data.tokensUsed} tokens</span>
              </>
            )}
          </div>
        </>
      )}
    </Card>
  );
}
