import { NavLink } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getHealth } from '../api/endpoints';
import { usePersona } from '../lib/PersonaContext';
import { ATTRIBUTION } from '../lib/format';

const NAV = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/log', label: 'Log' },
  { to: '/insights', label: 'Insights' },
  { to: '/reminders', label: 'Reminders' },
  { to: '/places', label: 'Places' },
];

/** Live "backend reachable" pill so a blank card is distinguishable from a down backend. */
function HealthPill() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['health'],
    queryFn: getHealth,
    refetchInterval: 30_000,
    retry: 2,
    retryDelay: 6_000,
  });
  const ok = data?.status === 'UP';
  const color = isLoading ? 'bg-ink-300' : ok ? 'bg-emerald-500' : 'bg-red-500';
  const text = isLoading ? 'Connecting…' : ok ? 'Backend live' : 'Backend offline';
  return (
    <span className="hidden items-center gap-1.5 rounded-full border border-ink-200 bg-white/70 px-2.5 py-1 text-xs font-medium text-ink-600 sm:inline-flex">
      <span className={`h-2 w-2 rounded-full ${color} ${ok ? 'animate-pulse' : ''}`} />
      {isError ? 'Backend offline' : text}
    </span>
  );
}

function BrandMark() {
  return (
    <NavLink to="/" className="flex flex-col leading-none">
      <span className="text-xl font-extrabold tracking-tight text-brand-700">Ashoo</span>
      <span className="text-[11px] font-medium text-ink-500">allergy, shoo</span>
    </NavLink>
  );
}

export default function Layout({ children }) {
  const { isDemo, meta } = usePersona();

  const navLinkCls = ({ isActive }) =>
    `rounded-lg px-3 py-2 text-sm font-semibold transition-colors ${
      isActive ? 'bg-brand-50 text-brand-700' : 'text-ink-600 hover:bg-ink-100/70 hover:text-ink-800'
    }`;

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-30 border-b border-ink-200/60 bg-white/70 backdrop-blur-md">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <BrandMark />
          <nav className="hidden items-center gap-1 md:flex">
            {NAV.map((n) => (
              <NavLink key={n.to} to={n.to} end={n.end} className={navLinkCls}>
                {n.label}
              </NavLink>
            ))}
          </nav>
          <HealthPill />
        </div>
        <nav className="flex gap-1 overflow-x-auto border-t border-ink-100 px-3 py-1.5 md:hidden">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} className={navLinkCls}>
              {n.label}
            </NavLink>
          ))}
        </nav>
      </header>

      {isDemo && (
        <div className="border-b border-amber-200 bg-amber-50/80">
          <div className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-2 text-xs font-medium text-amber-800 sm:px-6">
            <span aria-hidden>🧪</span>
            Viewing {meta.name} in {meta.location}, a seeded demo persona ({meta.blurb}). All figures
            are illustrative synthetic data.
          </div>
        </div>
      )}

      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">{children}</main>

      <footer className="mt-8 border-t border-ink-200/60 bg-white/50">
        <div className="mx-auto max-w-6xl px-4 py-6 text-xs text-ink-500 sm:px-6">
          <p className="font-medium text-ink-600">
            Ashoo is not a medical device. It does not diagnose, treat, or prescribe. Always carry
            your prescribed medication and consult your doctor for medical decisions.
          </p>
          <p className="mt-2">{ATTRIBUTION}</p>
        </div>
      </footer>
    </div>
  );
}
