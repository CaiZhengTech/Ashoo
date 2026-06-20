import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getSymptoms,
  createSymptom,
  updateSymptom,
  deleteSymptom,
  getLocations,
  getMedications,
  computeCorrelation,
} from '../api/endpoints';
import { errorMessage } from '../api/client';
import {
  Card,
  Button,
  Field,
  Input,
  Textarea,
  Select,
  Skeleton,
  EmptyState,
  ErrorState,
  SectionTitle,
  Slider,
} from '../components/ui';
import {
  daysAgoIso,
  formatDateTime,
  severityLabel,
  severityColor,
} from '../lib/format';
import { useToast } from '../lib/ToastContext';

/** A local datetime-input value (yyyy-MM-ddThh:mm) for a Date, in local time. */
function toLocalInput(date) {
  const d = new Date(date);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

/** Local datetime value (yyyy-MM-ddThh:mm) for the input default = now. */
function nowLocalInput() {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

function SymptomForm({ locations, medications, editing, onDone, onCancel }) {
  const qc = useQueryClient();
  const [loggedAt, setLoggedAt] = useState(
    editing ? new Date(editing.loggedAt).toISOString().slice(0, 16) : nowLocalInput()
  );
  const [severity, setSeverity] = useState(editing?.severity ?? 3);
  const [notes, setNotes] = useState(editing?.notes ?? '');
  const [locationId, setLocationId] = useState(editing?.locationId ?? '');
  const [cityName, setCityName] = useState(editing?.cityName ?? '');
  const [meds, setMeds] = useState(editing?.medicationsUsed?.map(String) ?? []);
  // Which quick-time chip is active (for highlight); null once a custom time is set.
  const [whenPreset, setWhenPreset] = useState(editing ? null : 'Now');

  const WHEN_PRESETS = [
    { label: 'Now', at: () => new Date() },
    { label: 'This morning', at: () => { const d = new Date(); d.setHours(8, 0, 0, 0); return d; } },
    { label: 'Yesterday', at: () => { const d = new Date(); d.setDate(d.getDate() - 1); d.setHours(8, 0, 0, 0); return d; } },
  ];

  const toast = useToast();
  const mutation = useMutation({
    mutationFn: (body) =>
      editing ? updateSymptom(editing.id, body) : createSymptom(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['symptoms'] });
      toast(editing ? 'Entry updated' : 'Symptom entry saved');
      onDone?.();
    },
  });

  function submit(e) {
    e.preventDefault();
    const body = {
      loggedAt: new Date(loggedAt).toISOString(),
      severity: Number(severity),
      notes: notes.trim() || null,
      // Prefer a saved location id; fall back to a typed city for ad-hoc logging.
      locationId: locationId ? Number(locationId) : null,
      cityName: locationId ? null : cityName.trim() || null,
      medicationsUsed: meds.length ? meds.map(Number) : null,
    };
    mutation.mutate(body);
  }

  function toggleMed(id) {
    setMeds((prev) =>
      prev.includes(id) ? prev.filter((m) => m !== id) : [...prev, id]
    );
  }

  return (
    <Card className="card-pad">
      <SectionTitle
        eyebrow={editing ? 'Editing entry' : 'New entry'}
        title={editing ? 'Update how you felt' : 'How did you feel?'}
      />
      <form onSubmit={submit} className="space-y-5">
        {/* Severity, the most important field, shown as a clear visual indicator. */}
        <div>
          <div className="mb-2 flex items-center gap-3">
            <span
              className={`grid h-12 w-12 shrink-0 place-items-center rounded-2xl text-lg font-extrabold ${severityColor(
                Number(severity)
              )}`}
            >
              {severity}
            </span>
            <div>
              <div className="text-sm font-semibold text-ink-800">
                {severityLabel(Number(severity))}
              </div>
              <div className="text-xs text-ink-500">How bad were your symptoms? Slide to set.</div>
            </div>
          </div>
          <Slider min={0} max={10} value={severity} onChange={(e) => setSeverity(e.target.value)} />
          <div className="mt-1 flex justify-between text-[10px] text-ink-400">
            <span>0 · none</span>
            <span>5 · moderate</span>
            <span>10 · severe</span>
          </div>
        </div>

        {/* When, quick chips lead, with an exact picker tucked in the same group. */}
        <Field label="When did this happen?">
          <div className="flex flex-wrap items-center gap-2">
            {WHEN_PRESETS.map((q) => {
              const active = whenPreset === q.label;
              return (
                <button
                  key={q.label}
                  type="button"
                  onClick={() => {
                    setLoggedAt(toLocalInput(q.at()));
                    setWhenPreset(q.label);
                  }}
                  className={`rounded-full border px-3 py-1.5 text-xs font-semibold transition-colors ${
                    active
                      ? 'border-brand-400 bg-brand-50 text-brand-700'
                      : 'border-ink-200 bg-white text-ink-600 hover:border-brand-300 hover:bg-brand-50/60'
                  }`}
                >
                  {q.label}
                </button>
              );
            })}
            <span className="text-xs text-ink-400">or</span>
            <input
              type="datetime-local"
              value={loggedAt}
              max={nowLocalInput()}
              onChange={(e) => {
                setLoggedAt(e.target.value);
                setWhenPreset(null);
              }}
              required
              className="flex-1 rounded-xl border border-ink-200 bg-white px-3 py-1.5 text-sm text-ink-800 transition-shadow focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-200"
            />
          </div>
        </Field>

        <Field label="Where" hint="Only saved places have tracked conditions, so only those count toward your model.">
          <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
            <option value="">Somewhere else (type a city)</option>
            {locations?.map((l) => (
              <option key={l.id} value={l.id}>
                {l.label} ({l.cityName})
              </option>
            ))}
          </Select>
          {!locationId && (
            <Input
              className="mt-2"
              placeholder="e.g. Sharon, MA"
              value={cityName}
              onChange={(e) => setCityName(e.target.value)}
            />
          )}
        </Field>

        <Field label="Notes" hint="Quick and free-form. Never shared with the AI briefing.">
          <Textarea
            placeholder="Itchy eyes after the afternoon walk…"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />
        </Field>

        {medications?.length > 0 && (
          <Field label="Medications used">
            <div className="flex flex-wrap gap-2">
              {medications.map((m) => {
                const on = meds.includes(String(m.id));
                return (
                  <button
                    type="button"
                    key={m.id}
                    onClick={() => toggleMed(String(m.id))}
                    className={`rounded-full border px-3 py-1 text-sm font-medium transition-colors ${
                      on
                        ? 'border-brand-300 bg-brand-50 text-brand-700'
                        : 'border-ink-200 bg-white text-ink-600 hover:bg-ink-50'
                    }`}
                  >
                    {m.name}
                  </button>
                );
              })}
            </div>
          </Field>
        )}

        {mutation.isError && (
          <p className="text-sm text-red-600">{errorMessage(mutation.error)}</p>
        )}

        <div className="flex items-center gap-2">
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : editing ? 'Save changes' : 'Log entry'}
          </Button>
          {editing && (
            <Button type="button" variant="secondary" onClick={onCancel}>
              Cancel
            </Button>
          )}
        </div>
      </form>
    </Card>
  );
}

function HistoryRow({ entry, onEdit }) {
  const qc = useQueryClient();
  const toast = useToast();
  const del = useMutation({
    mutationFn: () => deleteSymptom(entry.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['symptoms'] });
      toast('Entry deleted');
    },
  });

  return (
    <li className="flex items-start gap-3 py-3">
      <span
        className={`mt-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-xl text-sm font-bold ${severityColor(
          entry.severity
        )}`}
        title={severityLabel(entry.severity)}
      >
        {entry.severity}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
          <span className="text-sm font-medium text-ink-800">
            {formatDateTime(entry.loggedAt)}
          </span>
          {entry.cityName && (
            <span className="text-xs text-ink-500">· {entry.cityName}</span>
          )}
          {entry.dataOrigin === 'SEEDED_SYNTHETIC' && (
            <span className="rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-700">
              synthetic
            </span>
          )}
        </div>
        {entry.notes && <p className="mt-0.5 text-sm text-ink-600">{entry.notes}</p>}
      </div>
      <div className="flex shrink-0 gap-1">
        <button
          onClick={() => onEdit(entry)}
          className="rounded-lg px-2 py-1 text-xs font-semibold text-brand-700 hover:bg-brand-50"
        >
          Edit
        </button>
        <button
          onClick={() => del.mutate()}
          disabled={del.isPending}
          className="rounded-lg px-2 py-1 text-xs font-semibold text-red-600 hover:bg-red-50"
        >
          {del.isPending ? '…' : 'Delete'}
        </button>
      </div>
    </li>
  );
}

const HISTORY_RANGES = [
  { key: '7', label: '7 days', days: 7 },
  { key: '30', label: '30 days', days: 30 },
  { key: '90', label: '3 months', days: 90 },
  { key: 'all', label: 'All', days: 100000 },
];
const PAGE_SIZE = 6;

/** History list with a range filter and progressive "show more" so it never becomes
 *  one enormous scroll. */
function HistoryCard({ symptoms, range, setRange, visible, setVisible, recomputing, onEdit }) {
  const cutoff = Date.now() - (HISTORY_RANGES.find((r) => r.key === range)?.days ?? 30) * 86400000;
  const filtered = (symptoms.data ?? [])
    .filter((e) => new Date(e.loggedAt).getTime() >= cutoff)
    .sort((a, b) => new Date(b.loggedAt) - new Date(a.loggedAt));
  const shown = filtered.slice(0, visible);

  return (
    <Card className="card-pad">
      <SectionTitle
        eyebrow="Your history"
        title="Logged days"
        action={recomputing ? <span className="text-xs text-ink-400">Recalibrating…</span> : null}
      />

      <div className="mb-3 flex flex-wrap gap-1 rounded-lg bg-ink-100/70 p-0.5">
        {HISTORY_RANGES.map((r) => (
          <button
            key={r.key}
            onClick={() => {
              setRange(r.key);
              setVisible(PAGE_SIZE);
            }}
            className={`rounded-md px-2.5 py-1 text-xs font-semibold transition-colors ${
              range === r.key ? 'bg-white text-brand-700 shadow-sm' : 'text-ink-500 hover:text-ink-700'
            }`}
          >
            {r.label}
          </button>
        ))}
      </div>

      {symptoms.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : symptoms.isError ? (
        <ErrorState message={errorMessage(symptoms.error)} onRetry={() => symptoms.refetch()} />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon="📝"
          title="No entries in this range"
          hint="Log a symptom day on the left, or widen the range, even a 0 (felt fine) helps the model learn."
        />
      ) : (
        <>
          <ul className="divide-y divide-ink-100">
            {shown.map((e) => (
              <HistoryRow key={e.id} entry={e} onEdit={onEdit} />
            ))}
          </ul>
          <div className="mt-3 flex items-center justify-between text-xs">
            <span className="text-ink-400">
              Showing {shown.length} of {filtered.length}
            </span>
            <div className="flex gap-3">
              {visible < filtered.length && (
                <button
                  onClick={() => setVisible((v) => v + PAGE_SIZE)}
                  className="font-semibold text-brand-700 hover:text-brand-800"
                >
                  Show more
                </button>
              )}
              {visible > PAGE_SIZE && (
                <button
                  onClick={() => setVisible(PAGE_SIZE)}
                  className="font-semibold text-ink-500 hover:text-ink-700"
                >
                  Collapse
                </button>
              )}
            </div>
          </div>
        </>
      )}
    </Card>
  );
}

export default function LogPage() {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(null);
  const [range, setRange] = useState('30');
  const [visible, setVisible] = useState(PAGE_SIZE);

  const locations = useQuery({ queryKey: ['locations'], queryFn: getLocations, retry: 0 });
  const medications = useQuery({ queryKey: ['medications'], queryFn: getMedications, retry: 0 });
  const symptoms = useQuery({
    queryKey: ['symptoms'],
    queryFn: () => getSymptoms(daysAgoIso(180), new Date().toISOString()),
    retry: 0,
  });

  const recompute = useMutation({ mutationFn: computeCorrelation });

  function handleDone() {
    setEditing(null);
    recompute.mutate(undefined, {
      onSettled: () => {
        qc.invalidateQueries({ queryKey: ['risk'] });
        qc.invalidateQueries({ queryKey: ['correlation'] });
        qc.invalidateQueries({ queryKey: ['briefing'] });
      },
    });
  }

  // Editing scrolls the form into view, on mobile the form sits above the list,
  // so without this the user taps "Edit" and sees no visible change.
  function startEdit(entry) {
    setEditing(entry);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-ink-800">Symptom log</h1>
        <p className="mt-1 text-sm text-ink-500">
          The more honestly you log, the sharper your personal model gets. Editing a past
          entry automatically recalibrates your thresholds.
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* key forces a fresh form instance when switching between "new" and editing a
            specific row, so the useState initial values re-read from `editing`. */}
        <SymptomForm
          key={editing ? `edit-${editing.id}` : 'new'}
          locations={locations.data}
          medications={medications.data}
          editing={editing}
          onDone={handleDone}
          onCancel={() => setEditing(null)}
        />

        <HistoryCard
          symptoms={symptoms}
          range={range}
          setRange={setRange}
          visible={visible}
          setVisible={setVisible}
          recomputing={recompute.isPending}
          onEdit={startEdit}
        />
      </div>
    </div>
  );
}
