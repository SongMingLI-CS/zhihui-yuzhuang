import { useEffect, useMemo, useRef } from 'react';
import { Check, Clock3, PackageCheck, ShieldCheck, Truck, X } from 'lucide-react';
import type { OrderCheckoutResponse, Product } from '../types/api';
import { useCountdown } from '../hooks/useCountdown';
import { formatClock } from '../lib/format';
import { Price } from './ProductCard';
import { useToast } from './Toast';

export interface SuccessData {
  resp: OrderCheckoutResponse;
  product: Product;
}

const CONFETTI_COLORS = [
  '#10b981',
  '#f59e0b',
  '#fbbf24',
  '#34d399',
  '#f97316',
  '#84cc16',
  '#0ea5e9',
];

/** 抢购成功庆祝弹窗：彩带 + 订单号 + 支付时限倒计时 + 直发须知 */
export default function OrderSuccessModal({
  data,
  onClose,
}: {
  data: SuccessData | null;
  onClose: () => void;
}) {
  const { toast } = useToast();
  const open = data != null;
  const closeRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const { parts, expired } = useCountdown(data?.resp.expireTime ?? null);

  const pieces = useMemo(
    () =>
      Array.from({ length: 22 }, (_, i) => ({
        left: `${(i * 4.7 + 3) % 100}%`,
        delay: `${(i % 7) * 0.12}s`,
        duration: `${2.4 + (i % 5) * 0.35}s`,
        color: CONFETTI_COLORS[i % CONFETTI_COLORS.length],
        w: 6 + (i % 3) * 2,
      })),
    [],
  );

  useEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement as HTMLElement;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const timer = window.setTimeout(() => closeRef.current?.focus(), 120);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = prev;
      window.clearTimeout(timer);
      document.removeEventListener('keydown', onKeyDown);
      returnFocusRef.current?.focus();
    };
  }, [open, onClose]);

  if (!data) return null;
  const { resp, product } = data;

  const copyOrderNo = async () => {
    try {
      await navigator.clipboard.writeText(resp.orderNo);
      toast('success', '订单号已复制');
    } catch {
      toast('error', '复制失败，请手动记录');
    }
  };

  return (
    <div className="fixed inset-0 z-[80] overflow-y-auto" role="dialog" aria-modal="true" aria-labelledby="order-success-title">
      <div className="absolute inset-0 animate-fade-in bg-slate-900/60" onClick={onClose} />

      {/* 彩带层 */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        {pieces.map((p, i) => (
          <span
            key={i}
            className="animate-confetti-fall absolute top-[-12px] rounded-[1px]"
            style={{
              left: p.left,
              width: p.w,
              height: p.w * 1.6,
              background: p.color,
              animationDelay: p.delay,
              animationDuration: p.duration,
            }}
          />
        ))}
      </div>

      <div className="relative mx-auto grid min-h-full w-full max-w-[400px] place-items-center px-4 py-10">
        <div className="w-full animate-pop-success overflow-hidden rounded-3xl bg-white shadow-2xl">
          <div className="relative px-6 pb-6 pt-8 text-center">
            <button
              ref={closeRef}
              type="button"
              onClick={onClose}
              className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-full bg-slate-100 text-slate-400 active:scale-95"
              aria-label="关闭"
            >
              <X size={16} />
            </button>
            <span className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-gradient-to-br from-emerald-500 to-green-600 text-white shadow-lg shadow-emerald-500/30">
              <Check size={32} strokeWidth={3} />
            </span>
            <h2 id="order-success-title" className="mt-3 text-[22px] font-extrabold text-slate-800">抢购成功！</h2>
            <p className="mt-1 text-[12px] text-slate-500">
              「{product.spuName}」已为你锁定
            </p>
          </div>

          <div className="mx-5 rounded-2xl bg-green-50/70 p-4 ring-1 ring-green-100">
            <div className="flex items-center justify-between">
              <span className="text-[11px] text-slate-400">订单编号</span>
              <button
                type="button"
                onClick={copyOrderNo}
                className="flex items-center rounded-md bg-white px-1.5 py-0.5 text-[10px] font-medium text-green-600 ring-1 ring-green-200 active:scale-95"
              >
                复制
              </button>
            </div>
            <p className="mt-0.5 font-digit text-[15px] font-bold tracking-wide text-slate-800">
              {resp.orderNo}
            </p>
            <div className="mt-3 flex items-center justify-between border-t border-dashed border-green-200 pt-3">
              <span className="text-[12px] text-slate-500">实付金额</span>
              <Price value={resp.totalAmount} big />
            </div>
          </div>

          <div className="mx-5 mt-3 flex items-center gap-2 rounded-2xl bg-amber-50 px-4 py-3 ring-1 ring-amber-100">
            <Clock3 size={15} className="shrink-0 text-amber-500" />
            <p className="text-[11px] leading-snug text-amber-700">
              {expired || resp.expireTime == null ? (
                '订单支付窗口已结束，超时未支付将自动取消'
              ) : (
                <>
                  请在 <span className="font-digit font-bold">{formatClock(parts)}</span> 内完成支付，
                  超时自动取消
                </>
              )}
            </p>
          </div>

          <div className="mx-5 mt-4 flex justify-center gap-3 pb-5 text-[10px] text-slate-400">
            <span className="flex items-center gap-1">
              <Truck size={11} />
              产地直发
            </span>
            <span className="flex items-center gap-1">
              <PackageCheck size={11} />
              合作社直发
            </span>
            <span className="flex items-center gap-1">
              <ShieldCheck size={11} />
              资质认证
            </span>
          </div>

          <button
            type="button"
            onClick={onClose}
            className="mx-5 mb-5 w-[calc(100%-40px)] rounded-xl bg-gradient-to-r from-green-600 to-emerald-500 py-3 text-[14px] font-bold text-white shadow-md shadow-green-600/20 active:scale-[0.98]"
          >
            完成
          </button>
        </div>
      </div>
    </div>
  );
}
