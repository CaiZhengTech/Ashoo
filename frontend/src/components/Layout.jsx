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
    retry: 0,
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
    <NavLink to="/" className="flex items-center gap-2.5">
      <span className="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-brand-400 to-brand-700 text-white shadow-sm">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden>
          <path d="M6 14c0-2.2 1.8-4 4-4s4-1.8 4-4" stroke="white" strokeWidth="2" strokeLinecap="round" />
          <path d="M5 18c0-1.7 1.4-3 3.2-3s3.3 1.3 3.3 3" stroke="white" strokeWidth="2" strokeLinecap="round" opacity="0.7" />
          <circle cx="16.5" cy="7.5" r="1.6" fill="white" />
        </svg>
      </span>
      <span className="flex flex-col leading-none">
        <span className="text-lg font-extrabold tracking-tight text-ink-800">Ashoo</span>
        <span className="text-[11px] font-medium text-ink-500">allergy, shoo</span>
      </span>
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
            Viewing <strong>{meta.name}</strong>, a seeded demo persona ({meta.blurb}). All figures
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
