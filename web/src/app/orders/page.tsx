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
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/cn';
import {
  CHANNEL_LABELS_SHORT,
  DEMO_NOTE,
  ORDER_STATUS_LABELS,
  ORDERS,
  type OrderRow,
  type OrderStatus,
} from '@/lib/demo';
import { formatCNY, formatInt } from '@/lib/format';

/** 状态 → 徽标语义（仅前端展示层） */
const STATUS_TONE: Record<OrderStatus, 'green' | 'amber' | 'red' | 'blue' | 'slate' | 'violet'> = {
  PENDING_PAY: 'slate',
  STOCK_CONFIRMED: 'violet',
  PICKING: 'amber',
  READY: 'blue',
  SHIPPED: 'green',
  ABNORMAL: 'red',
};

const STATUS_ORDER: OrderStatus[] = [
  'PENDING_PAY',
  'STOCK_CONFIRMED',
  'PICKING',
  'READY',
  'SHIPPED',
  'ABNORMAL',
];

const SOURCE_ICON: Record<string, ReactNode> = {
  H5_PRIVATE: <Smartphone size={15} />,
  DOUYIN: <Music2 size={15} />,
  KUAISHOU: <Play size={15} />,
  B2B_PORTAL: <Building2 size={15} />,
};

export default function OrdersPage() {
  const [rows, setRows] = useState<OrderRow[]>(ORDERS);
  const [statusFilter, setStatusFilter] = useState<OrderStatus | 'ALL'>('ALL');
  const [query, setQuery] = useState('');

  const statusCount = useMemo(() => {
    const map = new Map<OrderStatus, number>();
    STATUS_ORDER.forEach((s) => map.set(s, 0));
    rows.forEach((r) => map.set(r.status, (map.get(r.status) ?? 0) + 1));
    return map;
  }, [rows]);

  const totalAmount = useMemo(() => rows.reduce((s, r) => s + r.amount, 0), [rows]);
  const shippedAmount = useMemo(
    () => rows.filter((r) => r.status === 'SHIPPED').reduce((s, r) => s + r.amount, 0),
    [rows],
  );
  const shippedQty = useMemo(
    () => rows.filter((r) => r.status === 'SHIPPED').reduce((s, r) => s + r.qty, 0),
    [rows],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
      if (!q) return true;
      return (
        r.orderNo.toLowerCase().includes(q) ||
        r.skuName.toLowerCase().includes(q) ||
        CHANNEL_LABELS_SHORT[r.source]?.toLowerCase().includes(q)
      );
    });
  }, [rows, statusFilter, query]);

  const shipOut = (id: number) => {
    setRows((prev) =>
      prev.map((r) => (r.id === id && r.status === 'READY' ? { ...r, status: 'SHIPPED' } : r)),
    );
  };

  // 出库流水：最新 READY/SHIPPED 记录（演示「出库就绪 → 已出库」流转）
  const outboundRows = useMemo(
    () =>
      [...rows]
        .filter((r) => r.status === 'READY' || r.status === 'SHIPPED')
        .sort((a, b) => b.id - a.id),
    [rows],
  );

  return (
    <div className="flex flex-col gap-5 p-5">
      {/* 页头 */}
      <div className="flex flex-wrap items-center gap-3">
        <div>
          <h2 className="text-lg font-bold text-slate-800">订单与出库流水</h2>
          <p className="mt-0.5 text-xs text-slate-400">
            渠道订单聚合 · 履约状态机（入库 → 备货 → 出库就绪 → 出库）
          </p>
        </div>
        <Badge tone="amber" className="ml-auto hidden md:inline-flex">
          <Info size={12} />
          {DEMO_NOTE}
        </Badge>
      </div>

      {/* 概览指标 */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard
          icon={<Warehouse size={18} />}
          iconCls="bg-brand-50 text-brand-600"
          value={`${rows.length} 单`}
          label="快照订单数 · 全渠道"
        />
        <StatCard
          icon={<Truck size={18} />}
          iconCls="bg-sky-50 text-sky-600"
          value={formatInt(shippedQty)}
          label="已出库件数"
        />
        <StatCard
          icon={<PackageCheck size={18} />}
          iconCls="bg-amber-50 text-amber-600"
          value={formatCNY(shippedAmount)}
          label="已出库金额"
        />
        <StatCard
          icon={<CheckCheck size={18} />}
          iconCls="bg-violet-50 text-violet-600"
          value={`${totalAmount > 0 ? Math.round((shippedAmount / totalAmount) * 100) : 0}%`}
          label="出库履约率（按金额）"
        />
      </div>

      {/* 履约状态机（出库流水看板） */}
      <Card
        icon={<PackageCheck size={16} />}
        title="履约出库流水看板"
        subtitle="订单削峰写流 → 异步消费 → 状态机推进 · 出库流水（演示）"
        bodyClassName="p-0"
      >
        <div className="scrollbar-thin flex items-stretch gap-2 overflow-x-auto px-5 py-4">
          {STATUS_ORDER.map((s, i) => {
            const n = statusCount.get(s) ?? 0;
            const isLast = i === STATUS_ORDER.length - 1;
            return (
              <div key={s} className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  onClick={() => setStatusFilter(s)}
                  className={cn(
                    'w-[132px] rounded-2xl border px-3 py-2.5 text-left transition',
                    s === 'ABNORMAL'
                      ? 'border-red-100 bg-red-50/40 hover:border-red-200'
                      : 'border-slate-200 bg-slate-50/40 hover:border-brand-200 hover:bg-brand-50/40',
                    statusFilter === s && 'ring-2 ring-brand-200',
                  )}
                >
                  <div className="flex items-center gap-1.5">
                    <span
                      className={cn(
                        'h-1.5 w-1.5 rounded-full',
                        s === 'ABNORMAL'
                          ? 'bg-red-500'
                          : s === 'SHIPPED'
                            ? 'bg-brand-500'
                            : s === 'READY'
                              ? 'bg-sky-500'
                              : 'bg-slate-300',
                      )}
                    />
                    <span className="text-[11px] font-medium text-slate-500">
                      {ORDER_STATUS_LABELS[s]}
                    </span>
                  </div>
                  <p className="num mt-1 text-lg font-bold text-slate-700">
                    {n}
                    <span className="ml-1 text-[10px] font-normal text-slate-300">单</span>
                  </p>
                </button>
                {!isLast && <span className="text-slate-300">›</span>}
              </div>
            );
          })}
        </div>
      </Card>

      {/* 订单明细 */}
      <Card
        title="订单明细"
        icon={<Info size={16} />}
        subtitle="全渠道聚合 · 含 B2B 集采 · 就绪单支持演示出库"
        bodyClassName="p-0"
        actions={
          <div className="relative">
            <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-300" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索单号 / 商品 / 渠道"
              className="h-8 w-56 rounded-lg border border-slate-200 bg-white pl-8 pr-2 text-xs text-slate-600 outline-none transition placeholder:text-slate-300 focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
            />
          </div>
        }
      >
        <div className="scrollbar-thin overflow-x-auto">
          <table className="w-full min-w-[940px] text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[11px] uppercase tracking-wide text-slate-400">
                <th className="px-5 py-3 font-medium">订单号 / 渠道</th>
                <th className="px-4 py-3 font-medium">商品</th>
                <th className="px-4 py-3 text-center font-medium">数量</th>
                <th className="px-4 py-3 text-right font-medium">金额</th>
                <th className="px-4 py-3 text-center font-medium">状态</th>
                <th className="px-5 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr
                  key={r.id}
                  className="group border-b border-slate-50 transition-colors last:border-0 hover:bg-brand-50/30"
                >
                  <td className="px-5 py-3.5">
                    <div className="flex items-center gap-2">
                      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500">
                        {SOURCE_ICON[r.source] ?? <Smartphone size={15} />}
                      </span>
                      <div>
                        <p className="num text-xs font-semibold text-slate-700">{r.orderNo}</p>
                        <p className="text-[10px] text-slate-400">
                          {CHANNEL_LABELS_SHORT[r.source] ?? r.source} · {r.createdAt}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <span className="max-w-[240px] truncate text-[13px] text-slate-600">
                      {r.skuName}
                    </span>
                  </td>
                  <td className="px-4 py-3.5 text-center">
                    <span className="num text-[13px] font-semibold text-slate-700">×{r.qty}</span>
                  </td>
                  <td className="px-4 py-3.5 text-right">
                    <span className="num text-[13px] font-semibold text-slate-800">
                      {formatCNY(r.amount)}
                    </span>
                  </td>
                  <td className="px-4 py-3.5 text-center">
                    <Badge tone={STATUS_TONE[r.status]} dot>
                      {ORDER_STATUS_LABELS[r.status]}
                    </Badge>
                  </td>
                  <td className="px-5 py-3.5 text-right">
                    {r.status === 'READY' ? (
                      <button
                        type="button"
                        onClick={() => shipOut(r.id)}
                        className="inline-flex items-center gap-1 rounded-lg border border-brand-200 px-2.5 py-1 text-xs font-medium text-brand-700 transition hover:bg-brand-700 hover:text-white"
                      >
                        <Truck size={12} />
                        出库
                      </button>
                    ) : r.status === 'ABNORMAL' ? (
                      <span className="inline-flex items-center gap-1 text-[11px] text-red-400">
                        <AlertTriangle size={12} />
                        需人工介入
                      </span>
                    ) : (
                      <span className="text-[11px] text-slate-300">—</span>
                    )}
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-5 py-10 text-center text-xs text-slate-400">
                    无匹配订单，试试调整筛选条件
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>

      {/* 待出库队列（出库就绪单，可一键出库） */}
      <Card
        icon={<PackageCheck size={16} />}
        title="待出库队列"
        subtitle="出库就绪订单按序出库，完成后计入已出库流水（演示）"
        bodyClassName="p-0"
      >
        {outboundRows.length > 0 ? (
          <ul className="divide-y divide-slate-50">
            {outboundRows.map((r) => (
              <li
                key={r.id}
                className="flex flex-wrap items-center gap-3 px-5 py-3 transition-colors hover:bg-slate-50/60"
              >
                <span
                  className={cn(
                    'grid h-8 w-8 shrink-0 place-items-center rounded-lg',
                    r.status === 'SHIPPED' ? 'bg-brand-50 text-brand-600' : 'bg-sky-50 text-sky-600',
                  )}
                >
                  {r.status === 'SHIPPED' ? <CheckCheck size={16} /> : <Warehouse size={16} />}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-xs font-semibold text-slate-700">
                    <span className="num">{r.orderNo}</span>
                    <Badge tone={STATUS_TONE[r.status]}>
                      {ORDER_STATUS_LABELS[r.status]}
                    </Badge>
                  </p>
                  <p className="mt-0.5 truncate text-[11px] text-slate-400">
                    {r.skuName} ×{r.qty} · {CHANNEL_LABELS_SHORT[r.source] ?? r.source} ·{' '}
                    {r.createdAt}
                  </p>
                </div>
                <span className="num text-[13px] font-semibold text-slate-700">
                  {formatCNY(r.amount)}
                </span>
                {r.status === 'READY' && (
                  <button
                    type="button"
                    onClick={() => shipOut(r.id)}
                    className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-2.5 py-1.5 text-[11px] font-medium text-white transition hover:bg-brand-800"
                  >
                    <Truck size={12} />
                    立即出库
                  </button>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-5 py-8 text-center text-xs text-slate-400">当前无出库就绪 / 已出库记录</p>
        )}
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
