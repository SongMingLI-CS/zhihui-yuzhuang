'use client';

import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Loader2, X } from 'lucide-react';

interface Props {
  open: boolean; title: string; description: string; confirmLabel?: string;
  countdownSeconds?: number; busy?: boolean; onConfirm: () => void; onCancel: () => void;
}

export function DangerConfirmDialog({ open, title, description, confirmLabel = '确认删除', countdownSeconds = 3, busy = false, onConfirm, onCancel }: Props) {
  const [remaining, setRemaining] = useState(countdownSeconds);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    setRemaining(countdownSeconds);
    const previous = document.body.style.overflow; document.body.style.overflow = 'hidden';
    const focusTimer = window.setTimeout(() => cancelRef.current?.focus(), 20);
    const timer = window.setInterval(() => setRemaining((value) => Math.max(0, value - 1)), 1000);
    const keydown = (event: KeyboardEvent) => { if (event.key === 'Escape' && !busy) onCancel(); };
    document.addEventListener('keydown', keydown);
    return () => { document.body.style.overflow = previous; clearTimeout(focusTimer); clearInterval(timer); document.removeEventListener('keydown', keydown); };
  }, [open, countdownSeconds, busy, onCancel]);
  if (!open) return null;
  return <div className="fixed inset-0 z-[80] grid place-items-center p-4" role="alertdialog" aria-modal="true" aria-labelledby="danger-title" aria-describedby="danger-description">
    <button type="button" className="absolute inset-0 bg-slate-950/45 backdrop-blur-[2px]" onClick={() => !busy && onCancel()} aria-label="取消危险操作" />
    <div className="relative w-full max-w-md rounded-2xl border border-red-100 bg-white p-5 shadow-2xl">
      <div className="flex items-start gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-red-50 text-red-600"><AlertTriangle size={20} /></span><div className="min-w-0 flex-1"><h2 id="danger-title" className="text-base font-bold text-slate-900">{title}</h2><p id="danger-description" className="mt-1 text-sm leading-6 text-slate-500">{description}</p></div><button type="button" onClick={onCancel} disabled={busy} aria-label="关闭" className="grid h-8 w-8 place-items-center rounded-lg text-slate-400 hover:bg-slate-100"><X size={16} /></button></div>
      <div className="mt-5 flex justify-end gap-2"><button ref={cancelRef} type="button" onClick={onCancel} disabled={busy} className="min-h-10 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-600 hover:bg-slate-50">取消</button><button type="button" onClick={onConfirm} disabled={busy || remaining > 0} className="inline-flex min-h-10 min-w-28 items-center justify-center gap-2 rounded-xl bg-red-600 px-4 text-sm font-semibold text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:bg-red-300">{busy && <Loader2 size={15} className="animate-spin" />}{remaining > 0 ? `${confirmLabel}（${remaining}s）` : confirmLabel}</button></div>
    </div>
  </div>;
}
