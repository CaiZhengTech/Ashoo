import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getConsent,
  acceptConsent,
  getMedications,
  addMedication,
  deleteMedication,
  getMedicationUsage,
  getReminderRules,
  addReminderRule,
  deleteReminderRule,
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
  Pill,
  Slider,
} from '../components/ui';
import { MED_TYPES, medTypeLabel, num } from '../lib/format';

// Mirrors ConsentService.CONSENT_DISCLAIMER on the backend so the user reads the
// exact statement they are agreeing to, before the server records it.
const CONSENT_DISCLAIMER =
  'I understand Ashoo is an informational wellness tool, not a medical device. ' +
  'It does not diagnose, treat, or prescribe, and its reminders are my own notes ' +
  'echoed back to me, not medical advice. I will always carry my prescribed ' +
  'medication and consult my doctor for medical decisions.';

/** The consent gate. No medication or reminder feature is reachable, on the
 *  frontend OR the backend, until this is accepted. */
function ConsentGate({ onAccept, accepting, error }) {
  const [checked, setChecked] = useState(false);
  return (
    <Card className="card-pad mx-auto max-w-2xl">
      <div className="mb-3 flex items-center gap-2">
        <span className="grid h-9 w-9 place-items-center rounded-xl bg-amber-100 text-amber-700">
          🤝
        </span>
        <h2 className="text-lg font-semibold text-ink-800">One quick agreement first</h2>
      </div>
      <p className="text-sm text-ink-600">
        Reminders in Ashoo are <strong>your own notes</strong> echoed back when conditions match
        thresholds you choose. Before turning them on, please read and accept:
      </p>
      <blockquote className="mt-4 rounded-xl border border-amber-200 bg-amber-50/70 p-4 text-sm leading-relaxed text-amber-900">
        {CONSENT_DISCLAIMER}
      </blockquote>
      <label className="mt-4 flex items-start gap-2 text-sm text-ink-700">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
          className="mt-0.5 h-4 w-4 accent-brand-600"
        />
        I have read and agree to the statement above.
      </label>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      <Button className="mt-4" disabled={!checked || accepting} onClick={onAccept}>
        {accepting ? 'Recording…' : 'I agree, enable reminders'}
      </Button>
    </Card>
  );
}

function MedicationsCard() {
  const qc = useQueryClient();
  const meds = useQuery({ queryKey: ['medications'], queryFn: getMedications, retry: 0 });
  const usage = useQuery({ queryKey: ['medications', 'usage'], queryFn: getMedicationUsage, retry: 0 });
  const [name, setName] = useState('');
  const [type, setType] = useState('INHALER');
  const [notes, setNotes] = useState('');

  const add = useMutation({
    mutationFn: () => addMedication({ name: name.trim(), type, notes: notes.trim() || null }),
    onSuccess: () => {
      setName('');
      setNotes('');
      qc.invalidateQueries({ queryKey: ['medications'] });
    },
  });
  const del = useMutation({
    mutationFn: (id) => deleteMedication(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['medications'] }),
  });

  const usageFor = (id) => usage.data?.find((u) => u.medicationId === id);

  return (
    <Card className="card-pad">
      <SectionTitle eyebrow="Your medications" title="Registered medications" />
      <p className="-mt-1 mb-4 text-sm text-ink-500">
        You register your own. Ashoo never suggests what to take, it only tracks what you tell it.
      </p>

      {meds.isLoading ? (
        <Skeleton className="h-20 w-full" />
      ) : meds.isError ? (
        <ErrorState message={errorMessage(meds.error)} onRetry={() => meds.refetch()} />
      ) : !meds.data?.length ? (
        <EmptyState icon="💊" title="No medications registered" hint="Add your first below." />
      ) : (
        <ul className="mb-4 space-y-2">
          {meds.data.map((m) => {
            const u = usageFor(m.id);
            const heavy = u && u.usesLast7Days > u.weeklyAverage90d && u.weeklyAverage90d > 0;
            return (
              <li
                key={m.id}
                className="flex items-center justify-between gap-3 rounded-xl border border-ink-200 bg-white p-3"
              >
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-ink-800">{m.name}</span>
                    <Pill className="border-ink-200 bg-ink-50 text-ink-600">
                      {medTypeLabel(m.type)}
                    </Pill>
                  </div>
                  {u && (
                    <p className={`mt-0.5 text-xs ${heavy ? 'text-orange-600' : 'text-ink-500'}`}>
                      {u.usesLast7Days} use{u.usesLast7Days === 1 ? '' : 's'} this week · 90-day
                      avg {num(u.weeklyAverage90d, 1)}/wk
                      {heavy && ' · above your average'}
                    </p>
                  )}
                </div>
                <button
                  onClick={() => del.mutate(m.id)}
                  className="rounded-lg px-2 py-1 text-xs font-semibold text-red-600 hover:bg-red-50"
                >
                  Remove
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) add.mutate();
        }}
        className="space-y-3 border-t border-ink-100 pt-4"
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name">
            <Input
              placeholder="e.g. Albuterol"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </Field>
          <Field label="Type">
            <Select value={type} onChange={(e) => setType(e.target.value)}>
              {MED_TYPES.map((t) => (
                <option key={t} value={t}>
                  {medTypeLabel(t)}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <Field label="Notes (optional)">
          <Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Rescue inhaler" />
        </Field>
        {add.isError && <p className="text-sm text-red-600">{errorMessage(add.error)}</p>}
        <Button type="submit" disabled={add.isPending || !name.trim()}>
          {add.isPending ? 'Adding…' : 'Add medication'}
        </Button>
      </form>
    </Card>
  );
}

function ReminderRulesCard() {
  const qc = useQueryClient();
  const rules = useQuery({ queryKey: ['reminder-rules'], queryFn: getReminderRules, retry: 0 });
  const meds = useQuery({ queryKey: ['medications'], queryFn: getMedications, retry: 0 });

  const [threshold, setThreshold] = useState(70);
  const [note, setNote] = useState('');
  const [medId, setMedId] = useState('');
  const [start, setStart] = useState('08:00');
  const [end, setEnd] = useState('20:00');

  const add = useMutation({
    mutationFn: () =>
      addReminderRule({
        riskScoreThreshold: Number(threshold),
        userNote: note.trim(),
        medicationId: medId ? Number(medId) : null,
        timeWindowStart: `${start}:00`,
        timeWindowEnd: `${end}:00`,
      }),
    onSuccess: () => {
      setNote('');
      qc.invalidateQueries({ queryKey: ['reminder-rules'] });
      qc.invalidateQueries({ queryKey: ['reminders', 'current'] });
    },
  });
  const del = useMutation({
    mutationFn: (id) => deleteReminderRule(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['reminder-rules'] });
      qc.invalidateQueries({ queryKey: ['reminders', 'current'] });
    },
  });

  const medName = (id) => meds.data?.find((m) => m.id === id)?.name ?? 'a medication';

  return (
    <Card className="card-pad">
      <SectionTitle eyebrow="When to nudge me" title="Reminder rules" />
      <p className="-mt-1 mb-4 text-sm text-ink-500">
        “When my risk crosses X during these hours, show me my note.” Time-aware so nothing fires
        at 3am unless you ask it to.
      </p>

      {rules.isLoading ? (
        <Skeleton className="h-20 w-full" />
      ) : rules.isError ? (
        <ErrorState message={errorMessage(rules.error)} onRetry={() => rules.refetch()} />
      ) : !rules.data?.length ? (
        <EmptyState icon="🔔" title="No rules yet" hint="Create your first reminder rule below." />
      ) : (
        <ul className="mb-4 space-y-2">
          {rules.data.map((r) => (
            <li
              key={r.id}
              className="flex items-start justify-between gap-3 rounded-xl border border-ink-200 bg-white p-3"
            >
              <div>
                <p className="text-sm font-medium text-ink-800">“{r.userNote}”</p>
                <p className="mt-0.5 text-xs text-ink-500">
                  PRI ≥ {Math.round(r.riskScoreThreshold)} · {medName(r.medicationId)} ·{' '}
                  {String(r.timeWindowStart).slice(0, 5)}-{String(r.timeWindowEnd).slice(0, 5)}
                </p>
              </div>
              <button
                onClick={() => del.mutate(r.id)}
                className="rounded-lg px-2 py-1 text-xs font-semibold text-red-600 hover:bg-red-50"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (note.trim()) add.mutate();
        }}
        className="space-y-3 border-t border-ink-100 pt-4"
      >
        <Field label={`Trigger when risk reaches ${threshold} or higher`}>
          <Slider min={40} max={95} value={threshold} onChange={(e) => setThreshold(e.target.value)} />
          <div className="mt-1 flex justify-between text-[10px] text-ink-400">
            <span>40 · elevated</span>
            <span>70 · high</span>
            <span>95 · severe</span>
          </div>
        </Field>
        <Field label="Your note" hint="This exact text is what you'll see, your words, not ours.">
          <Textarea
            placeholder="Pack my inhaler before heading out."
            value={note}
            onChange={(e) => setNote(e.target.value)}
            required
          />
        </Field>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Medication">
            <Select value={medId} onChange={(e) => setMedId(e.target.value)}>
              <option value="">No medication</option>
              {meds.data?.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="From">
            <Input type="time" value={start} onChange={(e) => setStart(e.target.value)} />
          </Field>
          <Field label="To">
            <Input type="time" value={end} onChange={(e) => setEnd(e.target.value)} />
          </Field>
        </div>
        {add.isError && <p className="text-sm text-red-600">{errorMessage(add.error)}</p>}
        <Button type="submit" disabled={add.isPending || !note.trim()}>
          {add.isPending ? 'Saving…' : 'Add rule'}
        </Button>
      </form>
    </Card>
  );
}

export default function RemindersPage() {
  const qc = useQueryClient();
  const consent = useQuery({ queryKey: ['consent'], queryFn: getConsent, retry: 0 });
  const accept = useMutation({
    mutationFn: acceptConsent,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['consent'] }),
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-ink-800">Medications &amp; reminders</h1>
        <p className="mt-1 text-sm text-ink-500">
          Keep your own medication list and set gentle, condition-based reminders, always in your
          own words, never medical advice.
        </p>
      </div>

      {consent.isLoading ? (
        <Card className="card-pad">
          <Skeleton className="h-32 w-full" />
        </Card>
      ) : consent.isError ? (
        <Card className="card-pad">
          <ErrorState message={errorMessage(consent.error)} onRetry={() => consent.refetch()} />
        </Card>
      ) : !consent.data?.consented ? (
        <ConsentGate
          onAccept={() => accept.mutate()}
          accepting={accept.isPending}
          error={accept.isError ? errorMessage(accept.error) : null}
        />
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          <MedicationsCard />
          <ReminderRulesCard />
        </div>
      )}
    </div>
  );
}
