// Small, dependency-free UI primitives shared across pages. Centralizing these
// keeps spacing, radius, and states consistent everywhere and avoids re-styling
// the same button or card in five places.
import { errorMessage } from '../api/client';

export function Card({ className = '', children, as: Tag = 'div', ...rest }) {
  return (
    <Tag className={`card ${className}`} {...rest}>
      {children}
    </Tag>
  );
}

export function SectionTitle({ eyebrow, title, action }) {
  return (
    <div className="mb-3 flex items-end justify-between gap-3">
      <div>
        {eyebrow && <div className="label-eyebrow mb-1">{eyebrow}</div>}
        <h2 className="text-lg font-semibold text-ink-800">{title}</h2>
      </div>
      {action}
    </div>
  );
}

const BTN_VARIANTS = {
  primary:
    'bg-brand-600 text-white hover:bg-brand-700 shadow-sm disabled:bg-brand-300',
  secondary:
    'bg-white text-ink-700 border border-ink-200 hover:bg-ink-50 disabled:text-ink-400',
  ghost: 'text-brand-700 hover:bg-brand-50 disabled:text-ink-400',
  danger:
    'bg-white text-red-600 border border-red-200 hover:bg-red-50 disabled:text-red-300',
};

export function Button({
  variant = 'primary',
  className = '',
  type = 'button',
  children,
  ...rest
}) {
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition-colors duration-150 disabled:cursor-not-allowed ${BTN_VARIANTS[variant]} ${className}`}
      {...rest}
    >
      {children}
    </button>
  );
}

export function Pill({ className = '', children }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-semibold ${className}`}
    >
      {children}
    </span>
  );
}

export function Field({ label, hint, children, htmlFor }) {
  return (
    <label htmlFor={htmlFor} className="block">
      <span className="mb-1 block text-sm font-medium text-ink-700">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-ink-500">{hint}</span>}
    </label>
  );
}

const INPUT_CLS =
  'w-full rounded-xl border border-ink-200 bg-white px-3 py-2 text-sm text-ink-800 placeholder:text-ink-400 transition-shadow focus:border-brand-400 focus:ring-2 focus:ring-brand-200 focus:outline-none';

export function Input(props) {
  return <input className={INPUT_CLS} {...props} />;
}
export function Textarea(props) {
  return <textarea className={`${INPUT_CLS} min-h-[80px] resize-y`} {...props} />;
}
export function Select({ children, ...props }) {
  return (
    <select className={INPUT_CLS} {...props}>
      {children}
    </select>
  );
}

/**
 * Styled range slider with a filled track up to the current value.
 *
 * Native range inputs don't show a "filled" portion cross-browser, so we drive
 * the CSS gradient's background-size from the value. Centralizing it means every
 * slider in the app feels the same and stays smooth.
 */
export function Slider({ min = 0, max = 100, value, onChange, ...props }) {
  const pct = ((Number(value) - min) / (max - min)) * 100;
  return (
    <input
      type="range"
      min={min}
      max={max}
      value={value}
      onChange={onChange}
      className="slider"
      style={{ backgroundSize: `${pct}% 100%` }}
      {...props}
    />
  );
}

/**
 * Small "i" help icon that reveals an explanation on hover or focus.
 *
 * Uses a CSS group so it works without any JS state, and is keyboard-focusable so
 * the explanation is reachable for everyone, not just mouse users.
 */
export function InfoTip({ text, className = '' }) {
  return (
    <span className={`group relative inline-flex ${className}`}>
      <button
        type="button"
        aria-label="More information"
        className="grid h-4 w-4 place-items-center rounded-full border border-ink-300 text-[10px] font-bold text-ink-500 transition-colors hover:border-brand-400 hover:text-brand-600"
      >
        i
      </button>
      <span
        role="tooltip"
        className="pointer-events-none absolute bottom-full left-1/2 z-30 mb-1.5 w-56 -translate-x-1/2 rounded-lg bg-ink-800 px-3 py-2 text-xs leading-snug text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
      >
        {text}
      </span>
    </span>
  );
}

/** Generic loading skeleton block. */
export function Skeleton({ className = 'h-4 w-full' }) {
  return <div className={`skeleton ${className}`} />;
}

/** Consistent empty state, icon, headline, supporting line, optional action. */
export function EmptyState({ icon = '🌤️', title, hint, action }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-ink-200 bg-ink-50/50 px-6 py-10 text-center">
      <div className="mb-2 text-2xl" aria-hidden>
        {icon}
      </div>
      <p className="font-medium text-ink-700">{title}</p>
      {hint && <p className="mt-1 max-w-sm text-sm text-ink-500">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/** Consistent error state used by every data card. */
export function ErrorState({ message, onRetry }) {
  return (
    <div className="rounded-xl border border-red-200 bg-red-50/70 px-4 py-5 text-center">
      <p className="text-sm font-medium text-red-700">Something went wrong</p>
      <p className="mt-1 text-xs text-red-600">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-3 rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-semibold text-red-700 hover:bg-red-50"
        >
          Try again
        </button>
      )}
    </div>
  );
}

/**
 * Wraps a TanStack Query result so every card renders loading/error/empty/data
 * the same way. Removes a pile of repetitive `if (isLoading)…` branches.
 *
 * @param query  the useQuery result
 * @param skeleton  what to show while loading
 * @param isEmpty  predicate on data → render emptyState instead of children
 */
export function QueryState({ query, skeleton, isEmpty, emptyState, children }) {
  if (query.isLoading) return skeleton || <Skeleton className="h-24 w-full" />;
  if (query.isError)
    return (
      <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
    );
  if (isEmpty && isEmpty(query.data)) return emptyState || null;
  return children(query.data);
}
