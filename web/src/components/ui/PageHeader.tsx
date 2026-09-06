import type { ReactNode } from 'react';

export function PageHeader({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-col gap-4 rounded-[22px] border border-white/80 bg-white/55 px-5 py-5 shadow-[0_1px_0_rgba(255,255,255,.8)_inset] backdrop-blur-sm sm:flex-row sm:items-end sm:justify-between sm:px-6">
      <div className="min-w-0">
        {eyebrow && <p className="mb-1.5 text-[11px] font-semibold uppercase tracking-[0.18em] text-brand-600">{eyebrow}</p>}
        <h2 className="text-[24px] font-bold leading-tight tracking-[-0.025em] text-slate-900 sm:text-[28px]">{title}</h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-500">{description}</p>
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
