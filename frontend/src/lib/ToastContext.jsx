import { createContext, useCallback, useContext, useState } from 'react';

/**
 * Tiny toast system: a `toast(message, type)` call drops a brief, auto-dismissing
 * notification in the corner. Used to confirm saves ("Entry saved") and surface
 * quick errors, so actions give clear feedback without a full page change.
 */
const ToastContext = createContext(null);

let nextId = 0;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);

  const dismiss = useCallback((id) => {
    setToasts((t) => t.filter((x) => x.id !== id));
  }, []);

  const toast = useCallback(
    (message, type = 'success') => {
      const id = ++nextId;
      setToasts((t) => [...t, { id, message, type }]);
      window.setTimeout(() => dismiss(id), 3200);
    },
    [dismiss]
  );

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <Toaster toasts={toasts} dismiss={dismiss} />
    </ToastContext.Provider>
  );
}

const TYPE_STYLES = {
  success: { cls: 'border-emerald-200 bg-white text-ink-800', icon: '✓', iconCls: 'bg-emerald-100 text-emerald-700' },
  error: { cls: 'border-red-200 bg-white text-ink-800', icon: '!', iconCls: 'bg-red-100 text-red-700' },
  info: { cls: 'border-brand-200 bg-white text-ink-800', icon: 'i', iconCls: 'bg-brand-100 text-brand-700' },
};

function Toaster({ toasts, dismiss }) {
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(92vw,22rem)] flex-col gap-2">
      {toasts.map((t) => {
        const s = TYPE_STYLES[t.type] || TYPE_STYLES.success;
        return (
          <div
            key={t.id}
            role="status"
            className={`animate-fade-in-up pointer-events-auto flex items-center gap-3 rounded-xl border px-4 py-3 shadow-card ${s.cls}`}
          >
            <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs font-bold ${s.iconCls}`}>
              {s.icon}
            </span>
            <span className="flex-1 text-sm font-medium">{t.message}</span>
            <button
              onClick={() => dismiss(t.id)}
              aria-label="Dismiss"
              className="shrink-0 rounded-md px-1 text-ink-400 hover:text-ink-700"
            >
              ✕
            </button>
          </div>
        );
      })}
    </div>
  );
}

export const useToast = () => useContext(ToastContext);
