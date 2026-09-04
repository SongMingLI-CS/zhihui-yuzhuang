import { type ReactNode } from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

/** 区块加载中 */
export function LoadingBlock({ text = '加载中…' }: { text?: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-10 text-slate-400">
      <Loader2 size={22} className="animate-spin text-brand-500" />
      <p className="text-xs">{text}</p>
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
        'flex flex-col items-center justify-center gap-2 rounded-xl border border-red-100 bg-red-50/60 px-4 py-6 text-center',
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
          className="mt-1 rounded-lg bg-red-600 px-3 py-1 text-xs font-medium text-white hover:bg-red-500"
        >
          重试
        </button>
      )}
    </div>
  );
}

export function EmptyBlock({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-8 text-center text-xs text-slate-400">
      {children}
    </div>
  );
}
