import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { seedDemo, computeCorrelation, setUserLocation, suggestPlaces } from '../api/endpoints';
import { usePersona, PERSONAS } from '../lib/PersonaContext';
import { useToast } from '../lib/ToastContext';
import { errorMessage } from '../api/client';
import { Card, Button, InfoTip } from './ui';

export default function DemoExplorer() {
  const qc = useQueryClient();
  const toast = useToast();
  const { persona, setPersona, userParam, setYouLocation } = usePersona();
  const [cityInput, setCityInput] = useState('');
  const [suggestions, setSuggestions] = useState([]);
  const [showDropdown, setShowDropdown] = useState(false);
  const [highlightIdx, setHighlightIdx] = useState(-1);
  const debounceRef = useRef(null);
  const wrapperRef = useRef(null);

  useEffect(() => {
    function handleClickOutside(e) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setShowDropdown(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  function handleInputChange(value) {
    setCityInput(value);
    setHighlightIdx(-1);

    if (debounceRef.current) clearTimeout(debounceRef.current);

    if (value.trim().length < 2) {
      setSuggestions([]);
      setShowDropdown(false);
      return;
    }

    debounceRef.current = setTimeout(async () => {
      try {
        const results = await suggestPlaces(value.trim());
        setSuggestions(results);
        setShowDropdown(results.length > 0);
      } catch {
        setSuggestions([]);
        setShowDropdown(false);
      }
    }, 300);
  }

  function selectCity(city) {
    setCityInput(city.cityName);
    setSuggestions([]);
    setShowDropdown(false);
    relocate.mutate(city.cityName);
  }

  function handleKeyDown(e) {
    if (!showDropdown || suggestions.length === 0) {
      if (e.key === 'Enter' && cityInput.trim()) {
        relocate.mutate(cityInput.trim());
      }
      return;
    }

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightIdx((i) => (i < suggestions.length - 1 ? i + 1 : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightIdx((i) => (i > 0 ? i - 1 : suggestions.length - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (highlightIdx >= 0) {
        selectCity(suggestions[highlightIdx]);
      } else if (cityInput.trim()) {
        relocate.mutate(cityInput.trim());
        setShowDropdown(false);
      }
    } else if (e.key === 'Escape') {
      setShowDropdown(false);
    }
  }

  const seed = useMutation({
    mutationFn: () => seedDemo(),
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

  const relocate = useMutation({
    mutationFn: (city) => setUserLocation(city),
    onSuccess: (data) => {
      if (data.city && setYouLocation) setYouLocation(data.city);
      qc.invalidateQueries();
      toast(`Location set to ${data.city}`);
      setCityInput('');
    },
  });

  const busy = seed.isPending || recompute.isPending || relocate.isPending;

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

      {persona === 'you' && (
        <div className="mt-4 border-t border-ink-100 pt-4">
          <div className="flex flex-wrap items-end gap-2" ref={wrapperRef}>
            <div className="relative flex-1">
              <label className="mb-1 block text-xs font-semibold text-ink-600">
                Your location
              </label>
              <input
                type="text"
                value={cityInput}
                onChange={(e) => handleInputChange(e.target.value)}
                onFocus={() => suggestions.length > 0 && setShowDropdown(true)}
                onKeyDown={handleKeyDown}
                placeholder="e.g. Boston, MA or London"
                className="w-full rounded-xl border border-ink-200 bg-white px-3 py-1.5 text-sm text-ink-800 transition-shadow focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-200"
                autoComplete="off"
              />
              {showDropdown && suggestions.length > 0 && (
                <ul className="absolute z-20 mt-1 max-h-48 w-full overflow-auto rounded-xl border border-ink-200 bg-white py-1 shadow-lg">
                  {suggestions.map((s, i) => (
                    <li
                      key={`${s.cityName}-${s.latitude}-${s.longitude}`}
                      onMouseDown={() => selectCity(s)}
                      onMouseEnter={() => setHighlightIdx(i)}
                      className={`cursor-pointer px-3 py-2 text-sm ${
                        i === highlightIdx
                          ? 'bg-brand-50 text-brand-700'
                          : 'text-ink-700 hover:bg-ink-50'
                      }`}
                    >
                      {s.cityName}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <Button
              variant="secondary"
              onClick={() => cityInput.trim() && relocate.mutate(cityInput.trim())}
              disabled={busy || !cityInput.trim()}
            >
              {relocate.isPending ? 'Setting…' : 'Set location'}
            </Button>
          </div>
          <p className="mt-1.5 text-[11px] text-ink-400">
            Changes your environment data. European cities include pollen; US cities get air quality
            and weather only.
          </p>
        </div>
      )}

      {(seed.isError || recompute.isError || relocate.isError) && (
        <p className="mt-2 text-xs text-red-600">
          {errorMessage(seed.error || recompute.error || relocate.error)}
        </p>
      )}
      {seed.isSuccess && !busy && (
        <p className="mt-2 text-xs text-emerald-700">Demo data refreshed for all personas.</p>
      )}
    </Card>
  );
}
