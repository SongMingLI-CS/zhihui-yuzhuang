import { useEffect, useRef, useState } from 'react';
import { MapPin, RefreshCw, ShieldCheck, Truck, X, Zap } from 'lucide-react';
import type { OrderCheckoutResponse, Product } from '../types/api';
import { checkoutOrder, toApiError } from '../lib/http';
import { DEMO_RECEIVER, ORDER_SOURCE } from '../config';
import { useToast } from './Toast';
import { Price } from './ProductCard';
import { productEmoji } from '../lib/productMeta';

/** 幂等键：优先 crypto.randomUUID()，非安全上下文回退时间戳随机串 */
function genIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

interface CheckoutDrawerProps {
  product: Product | null;
  onClose: () => void;
  onSuccess: (resp: OrderCheckoutResponse) => void;
  onSoldOut: () => void;
}

/** 底部弹出确认下单抽屉：收货人/电话/地址 + 一键演示地址 + 抢购提交状态机 */
export default function CheckoutDrawer({
  product,
  onClose,
  onSuccess,
  onSoldOut,
}: CheckoutDrawerProps) {
  const { toast } = useToast();
  const [recipientName, setRecipientName] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);
  const open = product != null;

  // 打开时锁背景滚动并自动聚焦首字段
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const t = window.setTimeout(() => nameRef.current?.focus(), 340);
    return () => {
      document.body.style.overflow = prev;
      window.clearTimeout(t);
    };
  }, [open]);

  if (!product) return null;

  const emoji = productEmoji(product);

  const handleClose = () => {
    if (!submitting) onClose();
  };

  const fillDemo = () => {
    setRecipientName(DEMO_RECEIVER.recipientName);
    setPhone(DEMO_RECEIVER.phone);
    setAddress(DEMO_RECEIVER.detailedAddress);
    toast('info', '已填入演示收货地址');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const name = recipientName.trim();
    const tel = phone.trim();
    const addr = address.trim();
    if (!name) {
      toast('error', '请填写收货人姓名');
      return;
    }
    if (!/^1[3-9]\d{9}$/.test(tel)) {
      toast('error', '请填写正确的 11 位手机号');
      return;
    }
    if (addr.length < 8) {
      toast('error', '请填写详细收货地址');
      return;
    }
    if (submitting) return;

    setSubmitting(true);
    const payload = {
      orderSource: ORDER_SOURCE,
      remark: 'C端H5秒杀场',
      items: [{ skuId: product.id, quantity: 1, expectedUnitPrice: product.price }],
      receiverAddress: { recipientName: name, phone: tel, detailedAddress: addr },
    };
    try {
      const resp = await checkoutOrder(payload, genIdempotencyKey());
      toast('success', '下单成功，正在生成订单…');
      onSuccess(resp);
    } catch (err) {
      const apiErr = toApiError(err);
      // 契约：库存售罄 409 / B2001 → 指定话术
      if (apiErr.code === 'B2001' || apiErr.httpStatus === 409) {
        toast('error', '手慢了，该批次特产已被抢光');
        onSoldOut();
      } else {
        toast('error', apiErr.message || '下单失败，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[70]">
      <div className="absolute inset-0 animate-fade-in bg-slate-900/55" onClick={handleClose} />

      <div className="absolute inset-x-0 bottom-0 mx-auto w-full max-w-[430px] animate-slide-up rounded-t-3xl bg-white safe-bottom">
        {/* 头部 */}
        <div className="flex items-center justify-between px-5 pb-1 pt-4">
          <div className="flex items-center gap-2">
            <Zap size={17} className="text-amber-500" />
            <h2 className="text-[16px] font-extrabold text-slate-800">确认抢购</h2>
          </div>
          <button
            type="button"
            onClick={handleClose}
            className="grid h-8 w-8 place-items-center rounded-full bg-slate-100 text-slate-400 active:scale-95"
            aria-label="关闭"
          >
            <X size={16} />
          </button>
        </div>

        {/* 商品摘要 */}
        <div className="mx-5 mt-2 flex items-center gap-3 rounded-2xl bg-slate-50 p-3 ring-1 ring-slate-100">
          <span className="grid h-14 w-14 shrink-0 place-items-center overflow-hidden rounded-xl bg-gradient-to-br from-green-100 to-amber-50 text-[30px]">
            {emoji}
          </span>
          <div className="min-w-0 flex-1">
            <p className="line-clamp-1 text-[14px] font-bold text-slate-800">{product.spuName}</p>
            <p className="mt-0.5 text-[11px] text-slate-400">于庄合作社直发 · 数量 1</p>
            <Price value={product.price} />
          </div>
        </div>

        {/* 收货表单 */}
        <form onSubmit={handleSubmit} className="mt-3 space-y-3 px-5 pb-2">
          <div>
            <div className="flex items-center justify-between">
              <label className="mb-1 block text-[12px] font-semibold text-slate-600">收货人</label>
              <button
                type="button"
                onClick={fillDemo}
                className="flex items-center gap-1 rounded-md bg-green-50 px-1.5 py-0.5 text-[10px] font-medium text-green-700 ring-1 ring-green-200 active:scale-95"
              >
                <RefreshCw size={10} />
                一键填入演示于庄地址
              </button>
            </div>
            <input
              ref={nameRef}
              value={recipientName}
              onChange={(e) => setRecipientName(e.target.value)}
              placeholder="请输入收货人姓名"
              maxLength={30}
              className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[14px] placeholder:text-slate-300 focus:border-green-500 focus:bg-white focus:ring-2 focus:ring-green-100"
            />
          </div>

          <div>
            <label className="mb-1 block text-[12px] font-semibold text-slate-600">联系电话</label>
            <input
              value={phone}
              onChange={(e) => setPhone(e.target.value.replace(/[^\d]/g, ''))}
              placeholder="请输入 11 位手机号"
              inputMode="numeric"
              maxLength={11}
              className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[14px] placeholder:text-slate-300 focus:border-green-500 focus:bg-white focus:ring-2 focus:ring-green-100"
            />
          </div>

          <div>
            <label className="mb-1 block text-[12px] font-semibold text-slate-600">收货地址</label>
            <textarea
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="省市区 + 详细地址"
              rows={2}
              maxLength={120}
              className="w-full resize-none rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[14px] placeholder:text-slate-300 focus:border-green-500 focus:bg-white focus:ring-2 focus:ring-green-100"
            />
          </div>

          <div className="flex items-center gap-1.5 rounded-xl bg-slate-50 px-3 py-2 text-[11px] text-slate-400">
            <ShieldCheck size={13} className="shrink-0 text-green-600" />
            产地合作社直发 · 订单信息已加密保护
          </div>

          {/* 提交按钮：idle → submitting(抢购中…) → 成功/失败 */}
          <button
            type="submit"
            disabled={submitting}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-red-500 to-orange-500 py-3.5 text-[15px] font-bold text-white shadow-lg shadow-red-500/25 transition active:scale-[0.98] disabled:opacity-70"
          >
            {submitting ? (
              <>
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />
                抢购中…
              </>
            ) : (
              <>
                <Zap size={16} />
                立即抢购 ¥{Number(product.price).toFixed(2)}
              </>
            )}
          </button>

          <p className="flex items-center justify-center gap-1 pb-1 text-center text-[10px] text-slate-300">
            <Truck size={11} className="text-green-400" />
            提交即表示同意《助农专场购买须知》
            <MapPin size={11} className="ml-1 text-green-400" />
            演示收货地址一键填充
          </p>
        </form>
      </div>
    </div>
  );
}
