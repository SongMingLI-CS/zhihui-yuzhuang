import { type ReactNode } from 'react';
import { cn } from '@/lib/cn';

interface CardProps {
  title?: ReactNode;
  subtitle?: ReactNode;
  icon?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
  /** 透明标题栏分割线 */
  padded?: boolean;
}

/** 白色圆角面板卡片（大屏/管理后台通用容器） */
export function Card({
  title,
  subtitle,
  icon,
  actions,
  children,
  className,
  bodyClassName,
  padded = true,
}: CardProps) {
  const hasHeader = title != null || actions != null;
  return (
    <section className={cn('panel flex flex-col overflow-hidden', className)}>
      {hasHeader && (
        <header className="flex flex-wrap items-start gap-2.5 border-b border-slate-100 px-4 py-4 sm:flex-nowrap sm:items-center sm:px-5">
          {icon != null && <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-700 sm:mt-0">{icon}</span>}
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold leading-5 text-slate-900">{title}</h3>
            {subtitle != null && (
              <p className="mt-1 text-xs leading-5 text-slate-500">{subtitle}</p>
            )}
          </div>
          {actions != null && <div className="flex w-full shrink-0 flex-wrap items-center gap-2 sm:w-auto">{actions}</div>}
        </header>
      )}
      <div className={cn('min-h-0 flex-1', padded && 'p-4 sm:p-5', bodyClassName)}>{children}</div>
    </section>
  );
}
