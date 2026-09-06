'use client';

import { type ReactNode } from 'react';
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react';
import { cn } from '@/lib/cn';

interface MetricCardProps {
  label: string;
  value: string;
  hint?: ReactNode;
  delta?: number;
  deltaSuffix?: string;
  icon: ReactNode;
  iconClass?: string;
  className?: string;
}

/** 顶部指标卡 */
export function MetricCard({
  label,
  value,
  hint,
  delta,
  deltaSuffix = '%',
  icon,
  iconClass = 'bg-brand-50 text-brand-600',
  className,
}: MetricCardProps) {
  const showDelta = delta !== undefined && !Number.isNaN(delta);
  return (
    <div
      className={cn(
        'panel group relative flex min-h-[120px] items-center gap-4 overflow-hidden px-5 py-4 transition-all duration-200 hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-md',
        className,
      )}
    >
      <span className={cn('grid h-11 w-11 shrink-0 place-items-center rounded-xl ring-1 ring-inset ring-black/[0.025]', iconClass)}>
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-xs font-semibold text-slate-500">{label}</p>
        <p className="num mt-1.5 truncate text-[23px] font-bold leading-none tracking-[-0.04em] text-slate-900">
          {value}
        </p>
        <div className="mt-1.5 flex items-center gap-2 text-[11px]">
          {showDelta && (
            <span
              className={cn(
                'inline-flex items-center gap-0.5 font-semibold',
                delta >= 0 ? 'text-brand-600' : 'text-red-500',
              )}
            >
              {delta >= 0 ? <ArrowUpRight size={12} /> : <ArrowDownRight size={12} />}
              {Math.abs(delta)}
              {deltaSuffix}
            </span>
          )}
          {!showDelta && <Minus size={12} className="text-slate-300" />}
          {hint != null && <span className="truncate text-slate-400">{hint}</span>}
        </div>
      </div>
    </div>
  );
}
