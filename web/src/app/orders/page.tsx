'use client';

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {

  Building2,
  CheckCheck,
  Info,
  Music2,
  PackageCheck,
  Play,
  RefreshCw,
  Search,
  Smartphone,
  Truck,
  Warehouse,
} from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyBlock, ErrorBlock, LoadingBlock } from '@/components/ui/StateView';
import { useToast } from '@/components/ui/Toast';
import { cn } from '@/lib/cn';
import { listOrders, markOrderReady, recoverAbnormalOrder, shipOrder, toApiError } from '@/lib/http';
import { CHANNEL_LABELS_SHORT } from '@/lib/demo';
import { formatCNY } from '@/lib/format';
import { Pagination } from '@/components/ui/Pagination';
import type { FulfillmentStatus, OrderStatus, OrderSummary } from '@/lib/types';

/** 交易状态（status，5 态，与后端 OrderStatus 对齐） */
const TRADE_LABELS: Record<OrderStatus, string> = {
  PENDING_PAY: '待支付',
  STOCK_CONFIRMED: '库存已确认',
  PROCESSING: '履约处理中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

const TRADE_TONE: Record<OrderStatus, 'green' | 'amber' | 'red' | 'blue' | 'slate' | 'violet'> = {
  PENDING_PAY: 'slate',
  STOCK_CONFIRMED: 'violet',
  PROCESSING: 'amber',
  COMPLETED: 'green',
  CANCELLED: 'red',
};

/** 履约状态（fulfillmentStatus · 出库流水维度，B 端看板主键，5 态） */
const FULFILL_LABELS: Record<FulfillmentStatus, string> = {
  PENDING: '待履约',
  PICKING: '拣货中',
  READY: '待出库',
  SHIPPED: '已出库',
  ABNORMAL: '异常',
};

const FULFILL_TONE: Record<FulfillmentStatus, 'green' | 'amber' | 'red' | 'blue' | 'slate' | 'violet'> = {
  PENDING: 'slate',
  PICKING: 'amber',
  READY: 'blue',
  SHIPPED: 'green',
  ABNORMAL: 'red',
};

const TRADE_TABS: Array<OrderStatus | 'ALL'> = ['ALL', 'PENDING_PAY', 'STOCK_CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED'];
const FULFILL_TABS: Array<FulfillmentStatus | 'ALL'> = ['ALL', 'PENDING', 'PICKING', 'READY', 'SHIPPED', 'ABNORMAL'];

const SOURCE_ICON: Record<string, ReactNode> = {
  H5_PRIVATE: <Smartphone size={15} />,
  DOUYIN: <Music2 size={15} />,
  KUAISHOU: <Play size={15} />,
  B2B_PORTAL: <Building2 size={15} />,
};

/** 概览/队列基于最近一页快照（后端单页上限 100），明细列表为服务端分页 */
const SUMMARY_PAGE_SIZE = 100;

function fmtTime(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function OrdersPage() {
  const { notify } = useToast();
  const queryClient = useQueryClient();
  const [tradeTab, setTradeTab] = useState<OrderStatus | 'ALL'>('ALL');
  const [fulfillTab, setFulfillTab] = useState<FulfillmentStatus | 'ALL'>('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 概览/分布/待出库队列：最近 100 单快照（统计标签语义为「近 100 单」）
  const { data: summaryData } = useQuery({
    queryKey: ['orders', 'summary'],
    queryFn: () => listOrders({ page: 1, pageSize: SUMMARY_PAGE_SIZE }),
  });
  const summaryRows = useMemo(() => summaryData?.items ?? [], [summaryData]);
  const totalOrders = summaryData?.total ?? summaryRows.length;

  const fulfillCount = useMemo(() => {
    const map = new Map<FulfillmentStatus, number>();
    (Object.keys(FULFILL_LABELS) as FulfillmentStatus[]).forEach((s) => map.set(s, 0));
    summaryRows.forEach((r) => map.set(r.fulfillmentStatus, (map.get(r.fulfillmentStatus) ?? 0) + 1));
    return map;
  }, [summaryRows]);
  const readyQty = fulfillCount.get('READY') ?? 0;
  const shippedAmount = useMemo(
    () => summaryRows.filter((r) => r.fulfillmentStatus === 'SHIPPED').reduce((s, r) => s + r.totalAmount, 0),
    [summaryRows],
  );
  const totalAmount = useMemo(() => summaryRows.reduce((s, r) => s + r.totalAmount, 0), [summaryRows]);
  const fulfillRate = totalAmount > 0 ? Math.round((shippedAmount / totalAmount) * 100) : 0;

  // 明细列表：服务端分页 + 双轴过滤 + keyword 模糊搜索
  const tableParams = useMemo(
    () => ({
      page,
      pageSize,
      status: tradeTab === 'ALL' ? undefined : tradeTab,
      fulfillmentStatus: fulfillTab === 'ALL' ? undefined : fulfillTab,
      keyword: keyword.trim() || undefined,
    }),
    [page, pageSize, tradeTab, fulfillTab, keyword],
  );
  const {
    data: tableData,
    isLoading: tableLoading,
    isError: tableError,
    error: tableErrorObj,
    refetch: tableRefetch,
  } = useQuery({
    queryKey: ['orders', 'table', tableParams],
    queryFn: () => listOrders(tableParams),
  });
  const tableRows = tableData?.items ?? [];
  const tableTotal = tableData?.total ?? 0;
  const tableTotalPages = tableData?.totalPages ?? 0;

  // 页码越界自动回拉（删除/出库导致末页变空）
  useEffect(() => {
    if (tableTotalPages >= 1 && page > tableTotalPages) setPage(tableTotalPages);
    else if (tableTotalPages === 0 && page !== 1) setPage(1);
  }, [tableTotalPages, page]);

  const onTrade = (s: OrderStatus | 'ALL') => {
    setTradeTab(s);
    setPage(1);
  };
  const onFulfill = (s: FulfillmentStatus | 'ALL') => {
    setFulfillTab(s);
    setPage(1);
  };

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['orders'] });

  const act = async (order: OrderSummary, kind: 'ship' | 'mark-ready' | 'recover') => {
    try {
      if (kind === 'ship') await shipOrder(order.orderNo);
      else if (kind === 'mark-ready') await markOrderReady(order.orderNo);
      else await recoverAbnormalOrder(order.orderNo);
      refresh();
      const msg =
        kind === 'ship' ? '已计入已出库流水。' : kind === 'mark-ready' ? '已进入待出库队列。' : '已恢复拣货，继续履约。';
      notify('success', '操作成功', `${order.orderNo} ${msg}`);
    } catch (e) {
      notify('error', '操作失败', toApiError(e).message);
    }
  };

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="ORDER FULFILLMENT"
        title="订单与出库流水"
        description="交易状态与履约状态双轴展示；拣货→待出库→出库→异常恢复全链路可操作"
        actions={
          <Badge tone="green">
            <Info size={12} />
            GET /orders 服务端分页
          </Badge>
        }
      />

      {/* 概览指标（近 100 单口径） */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard icon={<Warehouse size={18} />} iconCls="bg-brand-50 text-brand-600" value={`${totalOrders} 单`} label="订单总数 · 本租户" />
        <StatCard icon={<PackageCheck size={18} />} iconCls="bg-sky-50 text-sky-600" value={`${readyQty} 单`} label="待出库队列（近 100 单）" />
        <StatCard icon={<Truck size={18} />} iconCls="bg-amber-50 text-amber-600" value={formatCNY(shippedAmount)} label="已出库金额（近 100 单）" />
        <StatCard icon={<CheckCheck size={18} />} iconCls="bg-violet-50 text-violet-600" value={`${fulfillRate}%`} label="出库履约率（近 100 单按金额）" />
      </div>

      {/* 双轴状态筛选 + 服务端搜索 */}
      <Card bodyClassName="p-4 sm:p-5">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400">交易状态</span>
            {TRADE_TABS.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => onTrade(s)}
                aria-pressed={tradeTab === s}
                className={cn(
                  'min-h-8 rounded-lg border px-2.5 text-xs font-medium transition',
                  tradeTab === s
                    ? 'border-brand-600 bg-brand-600 text-white'
                    : 'border-slate-200 bg-white text-slate-600 hover:border-brand-300',
                )}
              >
                {s === 'ALL' ? '全部' : TRADE_LABELS[s]}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400">履约状态</span>
            {FULFILL_TABS.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => onFulfill(s)}
                aria-pressed={fulfillTab === s}
                className={cn(
                  'min-h-8 rounded-lg border px-2.5 text-xs font-medium transition',
                  fulfillTab === s
                    ? s === 'ABNORMAL'
                      ? 'border-red-600 bg-red-600 text-white'
                      : 'border-brand-600 bg-brand-600 text-white'
                    : s === 'ABNORMAL'
                      ? 'border-red-200 bg-red-50/40 text-red-600 hover:border-red-300'
                      : 'border-slate-200 bg-white text-slate-600 hover:border-brand-300',
                )}
              >
                {s === 'ALL' ? '全部' : `${FULFILL_LABELS[s]} ${fulfillCount.get(s) ?? 0}`}
              </button>
            ))}
          </div>
          <div className="relative max-w-sm">
            <Search size={14} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(1);
              }}
              placeholder="搜索订单号 / 收货人（服务端模糊匹配）"
              className="h-10 w-full rounded-xl border border-slate-200 bg-white pl-9 pr-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </div>
        </div>
      </Card>
      {/* 订单明细（服务端分页 + 行内履约操作） */}
      <Card
        icon={<PackageCheck size={16} />}
        title="订单明细"
        subtitle="交易/履约双状态徽标；READY 可出库、PICKING 可标记待出库、ABNORMAL 可恢复拣货"
        bodyClassName="p-0"
      >
        {tableLoading ? (
          <LoadingBlock text="加载订单…" />
        ) : tableError ? (
          <ErrorBlock message={toApiError(tableErrorObj).message} onRetry={() => tableRefetch()} />
        ) : tableRows.length === 0 ? (
          <EmptyBlock>没有匹配订单，请调整筛选或搜索条件。</EmptyBlock>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[900px] text-sm">
                <thead>
                  <tr className="border-b border-slate-100 text-left text-[11px] uppercase tracking-wider text-slate-400">
                    <th className="px-4 py-3 font-semibold sm:px-5">订单号 / 渠道</th>
                    <th className="px-4 py-3 font-semibold">交易状态</th>
                    <th className="px-4 py-3 font-semibold">履约状态</th>
                    <th className="px-4 py-3 font-semibold">收货人</th>
                    <th className="px-4 py-3 font-semibold">下单时间</th>
                    <th className="px-4 py-3 text-right font-semibold">金额</th>
                    <th className="px-4 py-3 text-right font-semibold">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-50">
                  {tableRows.map((r) => (
                    <tr key={r.orderNo} className="transition-colors hover:bg-slate-50/60">
                      <td className="px-4 py-3 sm:px-5">
                        <p className="num flex items-center gap-1.5 text-xs font-semibold text-slate-700">
                          <span className="text-slate-300">{SOURCE_ICON[r.orderSource]}</span>
                          {r.orderNo}
                        </p>
                        <p className="mt-0.5 text-[11px] text-slate-400">{CHANNEL_LABELS_SHORT[r.orderSource] ?? r.orderSource}</p>
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={TRADE_TONE[r.status]}>{TRADE_LABELS[r.status]}</Badge>
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={FULFILL_TONE[r.fulfillmentStatus]}>{FULFILL_LABELS[r.fulfillmentStatus]}</Badge>
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-600">{r.recipientName || '-'}</td>
                      <td className="px-4 py-3 text-xs text-slate-500">{fmtTime(r.createdAt)}</td>
                      <td className="num px-4 py-3 text-right text-xs font-semibold text-slate-700">{formatCNY(r.totalAmount)}</td>
                      <td className="px-4 py-3 text-right">
                        {r.fulfillmentStatus === 'READY' && (
                          <button
                            type="button"
                            onClick={() => act(r, 'ship')}
                            className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-2.5 py-1.5 text-[11px] font-medium text-white transition hover:bg-brand-800"
                          >
                            <Truck size={12} />
                            立即出库
                          </button>
                        )}
                        {r.fulfillmentStatus === 'PICKING' && (
                          <button
                            type="button"
                            onClick={() => act(r, 'mark-ready')}
                            className="inline-flex items-center gap-1 rounded-lg border border-sky-200 bg-sky-50 px-2.5 py-1.5 text-[11px] font-medium text-sky-700 transition hover:bg-sky-100"
                          >
                            <PackageCheck size={12} />
                            标记待出库
                          </button>
                        )}
                        {r.fulfillmentStatus === 'ABNORMAL' && (
                          <button
                            type="button"
                            onClick={() => act(r, 'recover')}
                            className="inline-flex items-center gap-1 rounded-lg border border-red-200 bg-red-50 px-2.5 py-1.5 text-[11px] font-medium text-red-600 transition hover:bg-red-100"
                          >
                            <RefreshCw size={12} />
                            恢复拣货
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between gap-2 border-t border-slate-100 px-4 py-2.5 sm:px-5">
              <p className="text-[11px] text-slate-400">共 {tableTotal} 单 · 第 {tableData?.page ?? page}/{Math.max(1, tableTotalPages)} 页</p>
              <Pagination
                page={Math.min(page, Math.max(1, tableTotalPages))}
                pageSize={pageSize}
                total={tableTotal}
                onPageChange={setPage}
                onPageSizeChange={(size: number) => {
                  setPageSize(size);
                  setPage(1);
                }}
              />
            </div>
          </>
        )}
      </Card>

      {/* 待出库队列（近 100 单中 READY，可一键出库） */}
      <Card
        icon={<Warehouse size={16} />}
        title="待出库队列"
        subtitle="READY 就绪单按序出库，出库后计入已出库流水"
        bodyClassName="p-0"
      >
        {(() => {
          const queue = summaryRows
            .filter((r) => r.fulfillmentStatus === 'READY')
            .sort((a, b) => (b.createdAt < a.createdAt ? -1 : 1));
          return queue.length > 0 ? (
            <ul className="divide-y divide-slate-50">
              {queue.map((r) => (
                <li key={r.orderNo} className="flex flex-wrap items-center gap-3 px-4 py-3.5 transition-colors hover:bg-slate-50/60 sm:px-5">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-sky-50 text-sky-600">
                    <Warehouse size={16} />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-2 text-xs font-semibold text-slate-700">
                      <span className="num">{r.orderNo}</span>
                      <Badge tone="blue">{FULFILL_LABELS.READY}</Badge>
                    </p>
                    <p className="mt-0.5 truncate text-[11px] text-slate-400">
                      {CHANNEL_LABELS_SHORT[r.orderSource] ?? r.orderSource} · {r.recipientName} · {fmtTime(r.createdAt)}
                    </p>
                  </div>
                  <span className="num text-[13px] font-semibold text-slate-700">{formatCNY(r.totalAmount)}</span>
                  <button
                    type="button"
                    onClick={() => act(r, 'ship')}
                    className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-2.5 py-1.5 text-[11px] font-medium text-white transition hover:bg-brand-800"
                  >
                    <Truck size={12} />
                    立即出库
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-5 py-8 text-center text-xs text-slate-400">
              当前无出库就绪（READY）订单——可将拣货中（PICKING）订单「标记待出库」补充队列
            </p>
          );
        })()}
      </Card>
    </div>
  );
}

function StatCard({
  icon,
  iconCls,
  value,
  label,
}: {
  icon: ReactNode;
  iconCls: string;
  value: string;
  label: string;
}) {
  return (
    <Card padded>
      <div className="flex items-center gap-3">
        <span className={cn('grid h-10 w-10 shrink-0 place-items-center rounded-xl', iconCls)}>
          {icon}
        </span>
        <div className="min-w-0">
          <p className="num truncate text-lg font-bold text-slate-800">{value}</p>
          <p className="truncate text-[11px] text-slate-400">{label}</p>
        </div>
      </div>
    </Card>
  );
}