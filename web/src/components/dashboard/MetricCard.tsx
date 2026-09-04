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
        'panel flex items-center gap-4 px-5 py-4 transition-shadow hover:shadow-md',
        className,
      )}
    >
      <span className={cn('grid h-11 w-11 shrink-0 place-items-center rounded-xl', iconClass)}>
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium text-slate-400">{label}</p>
        <p className="num mt-1 truncate text-[22px] font-bold leading-none text-slate-800">
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
