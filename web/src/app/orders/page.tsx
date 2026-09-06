'use client';

import { useMemo, useState, type ReactNode } from 'react';
import {
  AlertTriangle,
  Building2,
  CheckCheck,
  Info,
  Music2,
  PackageCheck,
  Play,
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
import { listOrders, shipOrder, toApiError } from '@/lib/http';
import { CHANNEL_LABELS_SHORT } from '@/lib/demo';
import { formatCNY } from '@/lib/format';
import { Pagination } from '@/components/ui/Pagination';
import { usePagination } from '@/hooks/usePagination';
import type { FulfillmentStatus, OrderStatus, OrderSummary } from '@/lib/types';

/** 交易状态（status · 订单履约状态轴之外的第二轴，5 态，与 OrderStatus 对齐） */
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

/** 履约状态（fulfillmentStatus · 出库流水维度，B 端看板主键，5 态与 FulfillmentStatus 对齐） */
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

/** 列表后端单页上限；当前种子数据 50 单可单页取全，超过时本页仅展示/统计前 100 条（演示说明） */
const SNAPSHOT_PAGE_SIZE = 100;
const PAGE_SIZE = 10;

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
  const pagination = usePagination(PAGE_SIZE);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['orders-snapshot'],
    queryFn: () => listOrders({ page: 1, pageSize: SNAPSHOT_PAGE_SIZE }),
  });

  const allRows = useMemo(() => data?.items ?? [], [data]);
  const totalOrders = data?.total ?? allRows.length;

  // 分布统计：看板以「履约（出库流水）」为轴
  const fulfillCount = useMemo(() => {
    const map = new Map<FulfillmentStatus, number>();
    (Object.keys(FULFILL_LABELS) as FulfillmentStatus[]).forEach((s) => map.set(s, 0));
    allRows.forEach((r) => map.set(r.fulfillmentStatus, (map.get(r.fulfillmentStatus) ?? 0) + 1));
    return map;
  }, [allRows]);

  const readyQty = fulfillCount.get('READY') ?? 0;
  const shippedAmount = useMemo(
    () => allRows.filter((r) => r.fulfillmentStatus === 'SHIPPED').reduce((s, r) => s + r.totalAmount, 0),
    [allRows],
  );
  const totalAmount = useMemo(() => allRows.reduce((s, r) => s + r.totalAmount, 0), [allRows]);
  const fulfillRate = totalAmount > 0 ? Math.round((shippedAmount / totalAmount) * 100) : 0;

  // 列表：交易状态轴 + 履约状态轴 双条件过滤，再本地关键词检索（订单号/收货人）
  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    return allRows.filter((r) => {
      if (tradeTab !== 'ALL' && r.status !== tradeTab) return false;
      if (fulfillTab !== 'ALL' && r.fulfillmentStatus !== fulfillTab) return false;
      if (!q) return true;
      return r.orderNo.toLowerCase().includes(q) || r.recipientName.toLowerCase().includes(q);
    });
  }, [allRows, tradeTab, fulfillTab, keyword]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / pagination.pageSize));
  const activePage = Math.min(pagination.page, pageCount);
  const pageRows = filtered.slice((activePage - 1) * pagination.pageSize, activePage * pagination.pageSize);

  const ship = (order: OrderSummary) => {
    shipOrder(order.orderNo)
      .then(() => {
        queryClient.invalidateQueries({ queryKey: ['orders-snapshot'] });
        notify('success', '出库完成', `${order.orderNo} 已计入已出库流水。`);
      })
      .catch((e) => notify('error', '出库失败', toApiError(e).message));
  };

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="ORDER FULFILLMENT"
        title="订单与出库流水"
        description="聚合私域、直播与集采订单；交易状态与履约状态双轴展示，待办与异常一目了然"
        actions={
          <Badge tone="green">
            <Info size={12} />
            GET /orders 实时数据
          </Badge>
        }
      />

      {/* 概览指标 */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard icon={<Warehouse size={18} />} iconCls="bg-brand-50 text-brand-600" value={`${totalOrders} 单`} label="订单总数 · 本租户" />
        <StatCard icon={<PackageCheck size={18} />} iconCls="bg-sky-50 text-sky-600" value={`${readyQty} 单`} label="待出库队列（READY）" />
        <StatCard icon={<Truck size={18} />} iconCls="bg-amber-50 text-amber-600" value={formatCNY(shippedAmount)} label="已出库金额" />
        <StatCard icon={<CheckCheck size={18} />} iconCls="bg-violet-50 text-violet-600" value={`${fulfillRate}%`} label="出库履约率（按金额）" />
      </div>

      {/* 双轴状态筛选 */}
      <Card bodyClassName="p-4 sm:p-5">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400">交易状态</span>
            {TRADE_TABS.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => { setTradeTab(s); pagination.setPage(1); }}
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
                onClick={() => { setFulfillTab(s); pagination.setPage(1); }}
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
              onChange={(e) => { setKeyword(e.target.value); pagination.setPage(1); }}
              placeholder="搜索订单号 / 收货人"
              className="h-10 w-full rounded-xl border border-slate-200 bg-white pl-9 pr-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </div>
        </div>
      </Card>
      {/* 订单列表（双轴状态徽标） */}
      <Card
        icon={<PackageCheck size={16} />}
        title="订单明细"
        subtitle="交易状态与履约状态双轴解耦展示 · 每行可单独定位待出库/异常单"
        bodyClassName="p-0"
      >
        {isLoading ? (
          <LoadingBlock text="加载订单…" />
        ) : isError ? (
          <ErrorBlock message={toApiError(error).message} onRetry={() => refetch()} />
        ) : pageRows.length === 0 ? (
          <EmptyBlock>没有匹配订单，请调整筛选条件。</EmptyBlock>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[860px] text-sm">
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
                  {pageRows.map((r) => (
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
                            onClick={() => ship(r)}
                            className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-2.5 py-1.5 text-[11px] font-medium text-white transition hover:bg-brand-800"
                          >
                            <Truck size={12} />
                            立即出库
                          </button>
                        )}
                        {r.fulfillmentStatus === 'ABNORMAL' && (
                          <span className="flex items-center justify-end gap-1 text-[11px] font-medium text-red-600">
                            <AlertTriangle size={12} />
                            需人工介入
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {filtered.length > 0 && (
              <Pagination page={activePage} pageSize={pagination.pageSize} total={filtered.length} onPageChange={pagination.setPage} onPageSizeChange={pagination.setPageSize} />
            )}
          </>
        )}
      </Card>

      {/* 待出库队列：READY 单可一键出库（POST /orders/{orderNo}/ship） */}
      <Card
        icon={<Warehouse size={16} />}
        title="待出库队列"
        subtitle="READY 就绪单按序出库，出库后计入已出库流水"
        bodyClassName="p-0"
      >
        {(() => {
          const queue = allRows
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
                    onClick={() => ship(r)}
                    className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-2.5 py-1.5 text-[11px] font-medium text-white transition hover:bg-brand-800"
                  >
                    <Truck size={12} />
                    立即出库
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-5 py-8 text-center text-xs text-slate-400">当前无出库就绪（READY）订单</p>
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