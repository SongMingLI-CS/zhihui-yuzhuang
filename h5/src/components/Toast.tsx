import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { CheckCircle2, Info, XCircle } from 'lucide-react';

type ToastType = 'success' | 'error' | 'info';

interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
}

interface ToastContextValue {
  toast: (type: ToastType, message: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

let seq = 0;

const ICON = {
  success: <CheckCircle2 size={18} className="shrink-0 text-emerald-400" />,
  error: <XCircle size={18} className="shrink-0 text-red-400" />,
  info: <Info size={18} className="shrink-0 text-sky-300" />,
};

const BAR = {
  success: 'bg-emerald-500',
  error: 'bg-red-500',
  info: 'bg-sky-400',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);

  const remove = useCallback((id: number) => {
    setItems((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (type: ToastType, message: string) => {
      const id = ++seq;
      setItems((prev) => [...prev.slice(-2), { id, type, message }]);
      window.setTimeout(() => remove(id), 2600);
    },
    [remove],
  );

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 top-0 z-[90] flex flex-col items-center gap-2 px-6 pt-[calc(env(safe-area-inset-top)+14px)]" aria-live="polite">
        {items.map((t) => (
          <div
            key={t.id}
            role="status"
            className="pointer-events-auto flex w-full max-w-[320px] animate-toast-in items-center gap-2 overflow-hidden rounded-xl bg-slate-900/95 py-2.5 pl-3 pr-4 text-[13px] text-white shadow-lg backdrop-blur"
          >
            <span className={`h-6 w-1 rounded-full ${BAR[t.type]}`} />
            {ICON[t.type]}
            <span className="min-w-0 flex-1 leading-snug">{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast 必须在 ToastProvider 内使用');
  }
  return ctx;
}
