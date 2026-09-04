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
        <header className="flex items-center gap-2 border-b border-slate-100 px-5 py-3.5">
          {icon != null && <span className="text-brand-600">{icon}</span>}
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-[15px] font-semibold text-slate-800">{title}</h3>
            {subtitle != null && (
              <p className="mt-0.5 truncate text-xs text-slate-400">{subtitle}</p>
            )}
          </div>
          {actions != null && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn('min-h-0 flex-1', padded && 'p-5', bodyClassName)}>{children}</div>
    </section>
  );
}
