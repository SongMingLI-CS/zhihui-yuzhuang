import { useCallback, useEffect, useState } from 'react';
import { ClipboardList, Loader2, PackageSearch, RefreshCw } from 'lucide-react';
import {
  cancelGuestOrder,
  listGuestOrders,
  listOrderCredentials,
  lookupGuestOrder,
  removeOrderCredential,
  toApiError,
} from '../lib/http';
import type { GuestOrderListEntry, GuestOrderLookupResponse } from '../types/api';

/**
 * 订单中心（阶段 E）：本人订单查询 / 物流状态 / 取消。
 *
 * <p>隐私与安全：查询与取消都必须同时提供「订单号 + 下单时下发的查询凭证」，
 * 服务端做 PBKDF2 校验并返回脱敏信息（手机号/地址打码），仅凭订单号无法读取。
 * 凭证保存在本机（localStorage），不含任何个人敏感信息。
 */
export const STATUS_LABELS: Record<string, string> = {
  STOCK_CONFIRMED: '待支付（库存已锁定）',
  PROCESSING: '已支付 / 履约中',
  CANCELLED: '已取消',
  COMPLETED: '已完成',
  PENDING_PAY: '待支付',
};

export const FULFILLMENT_LABELS: Record<string, string> = {
  PENDING: '待履约',
  PICKING: '拣货中',
  READY: '待出库',
  SHIPPED: '已发货',
  ABNORMAL: '异常',
};

export function fmtTime(ms?: number | null): string {
  if (!ms) return '—';
  const d = new Date(ms);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('zh-CN', { hour12: false });
}

export default function OrderCenter() {
  const stored = listOrderCredentials();
  const [orderNo, setOrderNo] = useState(stored[0]?.orderNo ?? '');
  const [queryToken, setQueryToken] = useState(stored[0]?.queryToken ?? '');
  const [result, setResult] = useState<GuestOrderLookupResponse | null>(null);
  const [entries, setEntries] = useState<GuestOrderListEntry[]>([]);
  const [listBusy, setListBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** 批量查询本机已保存凭证对应的订单（仅返回凭证校验通过者）。 */
  const refreshList = useCallback(async () => {
    const local = listOrderCredentials();
    if (local.length === 0) {
      setEntries([]);
      return;
    }
    setListBusy(true);
    try {
      setEntries(await listGuestOrders(local));
    } catch {
      setEntries([]);
    } finally {
      setListBusy(false);
    }
  }, []);

  useEffect(() => {
    void refreshList();
  }, [refreshList]);

  const run = async (fn: () => Promise<GuestOrderLookupResponse>) => {
    setBusy(true);
    setError(null);
    try {
      setResult(await fn());
      void refreshList();
    } catch (err) {
      setResult(null);
      setError(toApiError(err).message);
    } finally {
      setBusy(false);
    }
  };

  const onLookup = () =>
    run(() => lookupGuestOrder({ orderNo: orderNo.trim(), queryToken: queryToken.trim() }));

  const onCancel = () => {
    if (!window.confirm('确认取消该订单？未支付订单取消后库存会自动回补。')) return;
    void run(() =>
      cancelGuestOrder({
        orderNo: orderNo.trim(),
        queryToken: queryToken.trim(),
        reason: '用户在本页取消',
      }),
    );
  };

  const cancellable =
    result != null && (result.status === 'STOCK_CONFIRMED' || result.status === 'PENDING_PAY');

  return (
    <section className="px-4 pt-6" aria-label="订单中心">
      <div className="flex items-center gap-2">
        <ClipboardList size={16} className="text-green-600" />
        <h2 className="text-[15px] font-bold text-slate-800">订单中心</h2>
        <span className="text-[11px] text-slate-400">订单号 + 查询凭证</span>
      </div>

      <div className="mt-3 rounded-2xl border border-slate-200 bg-white p-3">
        {(entries.length > 0 || stored.length > 0) && (
          <div className="mb-3">
            <div className="mb-1.5 flex items-center gap-2">
              <span className="text-[11px] font-medium text-slate-600">我的订单（本机记录）</span>
              <button
                type="button"
                onClick={() => void refreshList()}
                disabled={listBusy}
                className="ml-auto inline-flex h-6 items-center gap-1 rounded-md border border-slate-200 px-2 text-[10px] text-slate-600 disabled:opacity-50"
              >
                {listBusy ? <Loader2 size={10} className="animate-spin" /> : <RefreshCw size={10} />}
                刷新
              </button>
            </div>
            <ul className="space-y-1">
              {(entries.length > 0 ? entries : stored.map((c) => ({
                orderNo: c.orderNo,
                valid: true,
                order: null,
                error: null,
              }))).map((entry) => (
                <li key={entry.orderNo}>
                  <button
                    type="button"
                    onClick={() => {
                      const local = stored.find((c) => c.orderNo === entry.orderNo);
                      setOrderNo(entry.orderNo);
                      setQueryToken(local?.queryToken ?? '');
                      setResult(entry.order ?? null);
                      setError(null);
                    }}
                    className={`flex w-full items-center gap-2 rounded-lg border px-2 py-1.5 text-left transition ${
                      orderNo === entry.orderNo
                        ? 'border-green-300 bg-green-50'
                        : 'border-slate-200 bg-white'
                    }`}
                  >
                    <span className="min-w-0 flex-1 truncate text-[11px] text-slate-700">
                      {entry.orderNo}
                    </span>
                    <span className="shrink-0 text-[10px] text-slate-500">
                      {entry.valid
                        ? STATUS_LABELS[entry.order?.status ?? ''] ?? entry.order?.status ?? '—'
                        : (entry.error ?? '凭证无效')}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        <label className="block">
          <span className="mb-1 block text-[11px] text-slate-500">订单号</span>
          <input
            value={orderNo}
            onChange={(e) => setOrderNo(e.target.value)}
            placeholder="如 ORD20260912..."
            className="h-9 w-full rounded-xl border border-slate-200 px-3 text-[12px] outline-none focus:border-green-400"
          />
        </label>
        <label className="mt-2 block">
          <span className="mb-1 block text-[11px] text-slate-500">
            查询凭证（下单成功时返回，仅需提供一次）
          </span>
          <input
            value={queryToken}
            onChange={(e) => setQueryToken(e.target.value)}
            placeholder="下单响应中的 queryToken"
            className="h-9 w-full rounded-xl border border-slate-200 px-3 text-[12px] outline-none focus:border-green-400"
          />
        </label>

        <button
          type="button"
          disabled={busy || !orderNo.trim() || !queryToken.trim()}
          onClick={onLookup}
          className="mt-3 flex h-9 w-full items-center justify-center gap-1.5 rounded-xl bg-green-600 text-[13px] font-semibold text-white disabled:opacity-50"
        >
          {busy ? <Loader2 size={14} className="animate-spin" /> : <PackageSearch size={14} />}
          {busy ? '查询中…' : '查询我的订单'}
        </button>

        {error && (
          <p className="mt-2 rounded-lg bg-red-50 px-2.5 py-2 text-[11px] text-red-600">{error}</p>
        )}

        {result && (
          <div className="mt-3 rounded-xl border border-dashed border-green-200 bg-green-50/40 p-3">
            <p className="text-[12px] font-semibold text-slate-800">{result.orderNo}</p>
            <p className="mt-1 text-[12px] text-slate-700">
              状态：{STATUS_LABELS[result.status ?? ''] ?? result.status ?? '—'}
              {result.fulfillmentStatus
                ? ` · ${FULFILLMENT_LABELS[result.fulfillmentStatus] ?? result.fulfillmentStatus}`
                : ''}
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              金额 ¥{Number(result.totalAmount).toFixed(2)} · 收货人 {result.recipientNameMasked}{' '}
              {result.recipientPhoneMasked} · {result.addressMasked}
            </p>
            {result.carrier || result.trackingNo ? (
              <p className="mt-1 text-[11px] text-slate-600">
                物流：{result.carrier ?? '—'} {result.trackingNo ?? ''}（发货{' '}
                {fmtTime(result.shippedAt)}）
              </p>
            ) : null}
            {result.cancelReason ? (
              <p className="mt-1 text-[11px] text-red-500">取消原因：{result.cancelReason}</p>
            ) : null}

            {cancellable && (
              <button
                type="button"
                disabled={busy}
                onClick={onCancel}
                className="mt-2 inline-flex h-8 items-center gap-1 rounded-lg border border-red-200 bg-white px-2.5 text-[11px] font-medium text-red-600 disabled:opacity-50"
              >
                取消订单
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                removeOrderCredential(result.orderNo);
                setResult(null);
                setOrderNo('');
                setQueryToken('');
              }}
              className="mt-2 ml-2 inline-flex h-8 items-center rounded-lg border border-slate-200 bg-white px-2.5 text-[11px] text-slate-500"
            >
              清除本机记录
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
