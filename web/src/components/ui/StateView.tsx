import { type ReactNode } from 'react';
import { AlertTriangle, Inbox, Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

/** 区块加载中 */
export function LoadingBlock({ text = '加载中…' }: { text?: string }) {
  return (
    <div className="flex min-h-[180px] flex-col items-center justify-center gap-3 py-10 text-slate-500" role="status" aria-live="polite">
      <Loader2 size={22} className="animate-spin text-brand-500" />
      <p className="text-sm">{text}</p>
    </div>
  );
}

/** 区块错误提示（可重试） */
export function ErrorBlock({
  title = '请求失败',
  message,
  onRetry,
  className,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex min-h-[180px] flex-col items-center justify-center gap-2 rounded-2xl border border-red-200 bg-red-50/60 px-5 py-8 text-center',
        className,
      )}
    >
      <AlertTriangle size={20} className="text-red-400" />
      <p className="text-sm font-semibold text-red-600">{title}</p>
      {message && <p className="max-w-md break-words text-xs leading-relaxed text-red-400">{message}</p>}
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 min-h-10 rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-red-500"
        >
          重试
        </button>
      )}
    </div>
  );
}

export function EmptyBlock({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-[180px] flex-col items-center justify-center gap-2 py-8 text-center text-sm text-slate-500">
      <span className="mb-1 grid h-11 w-11 place-items-center rounded-2xl bg-slate-100 text-slate-400"><Inbox size={20} /></span>
      {children}
    </div>
  );
}

export function SkeletonBlock({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-1" role="status" aria-label="内容加载中">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="h-12 animate-pulse rounded-xl bg-slate-100" style={{ opacity: 1 - index * 0.1 }} />
      ))}
    </div>
  );
}
