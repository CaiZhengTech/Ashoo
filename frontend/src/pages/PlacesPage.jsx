import { useEffect, useRef, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getLocations,
  addLocation,
  deleteLocation,
  updateLocation,
  getRecentSearches,
  getConditionsByCity,
  suggestPlaces,
  exportCsvUrl,
  triggerIngestion,
} from '../api/endpoints';
import { errorMessage } from '../api/client';
import {
  Card,
  Button,
  Field,
  Input,
  Skeleton,
  EmptyState,
  ErrorState,
  SectionTitle,
  Pill,
} from '../components/ui';
import ConditionsReadout from '../components/ConditionsReadout';
import { formatRelative, daysAgoIso } from '../lib/format';
import { useToast } from '../lib/ToastContext';

function SavedLocations() {
  const qc = useQueryClient();
  const toast = useToast();
  const locations = useQuery({ queryKey: ['locations'], queryFn: getLocations, retry: 0 });
  const [label, setLabel] = useState('');
  const [city, setCity] = useState('');

  const add = useMutation({
    mutationFn: () =>
      addLocation({ label: label.trim(), cityName: city.trim(), isPrimary: !locations.data?.length }),
    onSuccess: () => {
      setLabel('');
      setCity('');
      qc.invalidateQueries({ queryKey: ['locations'] });
      toast('Place added');
    },
  });
  const del = useMutation({
    mutationFn: (id) => deleteLocation(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['locations'] });
      toast('Place removed');
    },
  });
  const makePrimary = useMutation({
    mutationFn: (loc) =>
      updateLocation(loc.id, {
        label: loc.label,
        cityName: loc.cityName,
        county: loc.county,
        country: loc.country,
        latitude: loc.latitude,
        longitude: loc.longitude,
        isPrimary: true,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['locations'] }),
  });

  return (
    <Card className="card-pad">
      <SectionTitle eyebrow="Saved places" title="Your locations" />
      <p className="-mt-1 mb-4 text-sm text-ink-500">
        Ashoo pre-fetches conditions for each saved place. Type a city, coordinates are resolved
        for you.
      </p>

      {locations.isLoading ? (
        <Skeleton className="h-20 w-full" />
      ) : locations.isError ? (
        <ErrorState message={errorMessage(locations.error)} onRetry={() => locations.refetch()} />
      ) : !locations.data?.length ? (
        <EmptyState icon="📍" title="No saved places" hint="Add Home or Work below." />
      ) : (
        <ul className="mb-4 space-y-2">
          {locations.data.map((l) => (
            <li
              key={l.id}
              className="flex items-center justify-between gap-3 rounded-xl border border-ink-200 bg-white p-3"
            >
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-medium text-ink-800">{l.label}</span>
                  {l.isPrimary && (
                    <Pill className="border-brand-200 bg-brand-50 text-brand-700">primary</Pill>
                  )}
                </div>
                {/* cityName already includes region + country from geocoding. */}
                <p className="text-xs text-ink-500">{l.cityName}</p>
              </div>
              <div className="flex items-center gap-1">
                {!l.isPrimary && (
                  <button
                    onClick={() => makePrimary.mutate(l)}
                    className="rounded-lg px-2 py-1 text-xs font-semibold text-brand-700 hover:bg-brand-50"
                  >
                    Make primary
                  </button>
                )}
                <button
                  onClick={() => del.mutate(l.id)}
                  className="rounded-lg px-2 py-1 text-xs font-semibold text-red-600 hover:bg-red-50"
                >
                  Remove
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (label.trim() && city.trim()) add.mutate();
        }}
        className="grid gap-3 border-t border-ink-100 pt-4 sm:grid-cols-2"
      >
        <Field label="Label">
          <Input placeholder="Home" value={label} onChange={(e) => setLabel(e.target.value)} required />
        </Field>
        <Field label="City / town">
          <Input
            placeholder="Sharon, MA"
            value={city}
            onChange={(e) => setCity(e.target.value)}
            required
          />
        </Field>
        {add.isError && (
          <p className="text-sm text-red-600 sm:col-span-2">{errorMessage(add.error)}</p>
        )}
        <div className="sm:col-span-2">
          <Button type="submit" disabled={add.isPending || !label.trim() || !city.trim()}>
            {add.isPending ? 'Locating…' : 'Add place'}
          </Button>
        </div>
      </form>
    </Card>
  );
}

/**
 * Search-anywhere box with a live suggestions dropdown.
 *
 * As the user types we debounce a call to the geocoder and show candidate places so
 * they can disambiguate (there are many "Sharon"s) before committing. When the box
 * is empty/focused we instead show recent searches (deduped by name) so a previously
 * looked-up place reappears here rather than as a stray result. Picking any item
 * loads that one place's conditions below, only ever one readout, so duplicates can't
 * stack up.
 */
function ConditionsSearch() {
  const qc = useQueryClient();
  const [text, setText] = useState('');
  const [selected, setSelected] = useState(null); // { cityName }
  const [open, setOpen] = useState(false);
  const [suggestions, setSuggestions] = useState([]);
  const boxRef = useRef(null);

  const recent = useQuery({ queryKey: ['recent-searches'], queryFn: getRecentSearches, retry: 0 });

  // Dedupe recent searches by display name, newest first.
  const recentUnique = [];
  const seen = new Set();
  for (const r of recent.data ?? []) {
    const key = (r.cityName || '').toLowerCase();
    if (key && !seen.has(key)) {
      seen.add(key);
      recentUnique.push(r);
    }
  }

  // Debounced geocoding suggestions while typing.
  useEffect(() => {
    const q = text.trim();
    if (q.length < 2) {
      setSuggestions([]);
      return;
    }
    const id = setTimeout(async () => {
      try {
        const results = await suggestPlaces(q);
        setSuggestions(results);
      } catch {
        setSuggestions([]);
      }
    }, 250);
    return () => clearTimeout(id);
  }, [text]);

  // Close the dropdown on outside click.
  useEffect(() => {
    function onClick(e) {
      if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false);
    }
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, []);

  const lookup = useQuery({
    queryKey: ['conditions', selected?.cityName],
    queryFn: () => getConditionsByCity(selected.cityName),
    enabled: !!selected,
    retry: 0,
  });

  function choose(cityName) {
    setSelected({ cityName });
    setText(cityName);
    setOpen(false);
    setSuggestions([]);
    // The lookup records a recent search server-side; refresh the list shortly after.
    setTimeout(() => qc.invalidateQueries({ queryKey: ['recent-searches'] }), 700);
  }

  const showRecent = open && text.trim().length < 2 && recentUnique.length > 0;
  const showSuggest = open && suggestions.length > 0;

  return (
    <Card className="card-pad">
      <SectionTitle eyebrow="Look it up" title="Conditions anywhere" />

      <div ref={boxRef} className="relative mb-3">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) choose(text.trim());
          }}
          className="flex gap-2"
        >
          <Input
            placeholder="Search a city, e.g. Amsterdam"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            aria-autocomplete="list"
          />
          <Button type="submit" disabled={!text.trim()}>
            Search
          </Button>
        </form>

        {(showSuggest || showRecent) && (
          <div className="absolute z-20 mt-1 w-full overflow-hidden rounded-xl border border-ink-200 bg-white shadow-card">
            {showRecent && (
              <div className="px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-400">
                Recent searches
              </div>
            )}
            {(showSuggest ? suggestions : recentUnique.map((r) => ({ cityName: r.cityName }))).map(
              (s, i) => (
                <button
                  key={`${s.cityName}-${i}`}
                  onClick={() => choose(s.cityName)}
                  className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink-700 hover:bg-brand-50"
                >
                  <span aria-hidden className="text-ink-400">
                    {showSuggest ? '📍' : '🕘'}
                  </span>
                  <span className="truncate">{s.cityName}</span>
                </button>
              )
            )}
          </div>
        )}
      </div>

      {lookup.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : lookup.isError ? (
        <ErrorState message={errorMessage(lookup.error)} onRetry={() => lookup.refetch()} />
      ) : lookup.data ? (
        <ConditionsReadout snapshot={lookup.data} />
      ) : (
        <EmptyState
          icon="🔎"
          title="Search a place"
          hint="Try Amsterdam for full pollen data, or Sharon, MA for US air quality."
        />
      )}

      {selected && lookup.data && (
        <p className="mt-3 text-[11px] text-ink-400">
          Looked up {formatRelative(new Date().toISOString())}. A map view is coming in a future
          version.
        </p>
      )}
    </Card>
  );
}

function DataPanel() {
  const qc = useQueryClient();
  const [from, setFrom] = useState(daysAgoIso(30).slice(0, 10));
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const refresh = useMutation({
    mutationFn: triggerIngestion,
    onSuccess: () => qc.invalidateQueries(),
  });

  const fromIso = new Date(`${from}T00:00:00Z`).toISOString();
  const toIso = new Date(`${to}T23:59:59Z`).toISOString();

  return (
    <Card className="card-pad">
      <SectionTitle eyebrow="Your data" title="Export &amp; refresh" />
      <p className="-mt-1 mb-4 text-sm text-ink-500">
        Download everything as CSV (your symptom logs joined with conditions), or pull the latest
        conditions now.
      </p>

      <div className="mb-4 grid gap-3 sm:grid-cols-2">
        <Field label="From">
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label="To">
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </Field>
      </div>

      <div className="flex flex-wrap gap-2">
        <a href={exportCsvUrl(fromIso, toIso)} target="_blank" rel="noreferrer" download>
          <Button>⬇ Download CSV</Button>
        </a>
        <Button variant="secondary" onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          {refresh.isPending ? 'Refreshing…' : '↻ Refresh conditions'}
        </Button>
      </div>
      {refresh.isSuccess && <p className="mt-2 text-sm text-emerald-700">Latest conditions pulled.</p>}
      {refresh.isError && <p className="mt-2 text-sm text-red-600">{errorMessage(refresh.error)}</p>}
    </Card>
  );
}

export default function PlacesPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-ink-800">Places</h1>
        <p className="mt-1 text-sm text-ink-500">
          Search conditions anywhere, save the places you care about, and export your records.
          Looking for the demo personas? They live on the dashboard now.
        </p>
      </div>

      <ConditionsSearch />

      <div className="grid gap-6 lg:grid-cols-2">
        <SavedLocations />
        <DataPanel />
      </div>
    </div>
  );
}
