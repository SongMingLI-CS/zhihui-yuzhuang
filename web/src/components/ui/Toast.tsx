'use client';

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import { cn } from '@/lib/cn';

type ToastTone = 'success' | 'error' | 'info';
interface ToastItem { id: number; tone: ToastTone; title: string; message?: string }
interface ToastApi { notify: (tone: ToastTone, title: string, message?: string) => void }

const ToastContext = createContext<ToastApi | null>(null);
const meta = {
  success: { icon: CheckCircle2, cls: 'text-emerald-700 bg-emerald-50 border-emerald-200' },
  error: { icon: AlertCircle, cls: 'text-red-700 bg-red-50 border-red-200' },
  info: { icon: Info, cls: 'text-sky-700 bg-sky-50 border-sky-200' },
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const idRef = useRef(0);
  const remove = useCallback((id: number) => setItems((prev) => prev.filter((item) => item.id !== id)), []);
  const notify = useCallback((tone: ToastTone, title: string, message?: string) => {
    const id = ++idRef.current;
    setItems((prev) => [...prev.slice(-2), { id, tone, title, message }]);
    window.setTimeout(() => remove(id), 3600);
  }, [remove]);
  const api = useMemo(() => ({ notify }), [notify]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed inset-x-3 top-3 z-[100] flex flex-col items-end gap-2 sm:inset-x-auto sm:right-5 sm:top-5" aria-live="polite">
        {items.map((item) => {
          const Icon = meta[item.tone].icon;
          return (
            <div key={item.id} role="status" className={cn('pointer-events-auto flex w-full items-start gap-3 rounded-2xl border px-4 py-3 shadow-xl backdrop-blur sm:w-[360px]', meta[item.tone].cls)}>
              <Icon size={19} className="mt-0.5 shrink-0" />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold">{item.title}</p>
                {item.message && <p className="mt-0.5 text-xs leading-5 opacity-80">{item.message}</p>}
              </div>
              <button type="button" onClick={() => remove(item.id)} className="rounded-lg p-1 opacity-60 transition hover:bg-black/5 hover:opacity-100" aria-label="关闭提示"><X size={14} /></button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const value = useContext(ToastContext);
  if (!value) throw new Error('useToast must be used within ToastProvider');
  return value;
}
