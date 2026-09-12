'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  Info,
  PackageCheck,
  PieChart,
  Radio,
  ShoppingCart,
  Timer,
  TrendingUp,
  Wallet,
} from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { MetricCard } from '@/components/dashboard/MetricCard';
import { TrendChart } from '@/components/dashboard/TrendChart';
import { SalesShareChart } from '@/components/dashboard/SalesShareChart';
import { RealtimeFeed } from '@/components/dashboard/RealtimeFeed';
import { SIMULATED_REALTIME_FEED } from '@/lib/config';
import { fetchDashboardSummary } from '@/lib/http';
import { formatDate, formatInt, formatYuan } from '@/lib/format';
import type { SalesShareItem, TrendPoint } from '@/lib/demo';

/** RealtimeFeed 客户端模拟的基准事件速率（仅作流语义演示基线，非经营数据）。 */
const FEED_TPS = 60;

const SHARE_COLORS = ['#2c724a', '#e9b949', '#c26e4b', '#5b8ff9', '#8fc9a2'];

export default function DashboardPage() {
  const [today, setToday] = useState<Date | null>(null);
  useEffect(() => setToday(new Date()), []);

  // 实时流水仅作客户端流语义演示，不联动真实经营指标
  const handleFeedStats = () => { /* no-op */ };

  // 经营数据全部来自 backend 真实聚合（JWT 租户域），每 60s 自动刷新
  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary'],
    queryFn: fetchDashboardSummary,
    refetchInterval: 60_000,
  });
  const summary = summaryQuery.data;

  const trend = useMemo<TrendPoint[]>(
    () =>
      (summary?.trend ?? []).map((d) => {
        const [, mm, dd] = d.date.split('-');
        return { label: `${mm}-${dd}`, orders: d.orderCount, revenue: d.salesAmount };
      }),
    [summary],
  );

  const salesShare = useMemo<SalesShareItem[]>(() => {
    const items = summary?.topProducts ?? [];
    const total = items.reduce((s, p) => s + p.salesAmount, 0) || 1;
    return items.map((p, i) => ({
      name: p.spuName,
      value: Math.round((p.salesAmount / total) * 1000) / 10,
      amount: p.salesAmount,
      color: SHARE_COLORS[i % SHARE_COLORS.length],
    }));
  }, [summary]);

  const failed = summaryQuery.isError;

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="OPERATIONS OVERVIEW"
        title="产业治理大盘"
        description={`于庄合作社今日经营快照 · ${today ? formatDate(today) : '今日'}`}
        actions={
          <Badge tone={failed ? 'red' : 'green'}>
            <Info size={12} />
            {failed ? '加载失败' : '实时经营数据'}
          </Badge>
        }
      />
      {/* 指标卡（真实聚合，非取消口径交易额） */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:gap-4 2xl:grid-cols-4">
        <MetricCard
          label="累计助农交易额"
          value={formatYuan(summary?.totalSales ?? 0)}
          hint="非取消口径 · 本租户全渠道"
          icon={<Wallet size={20} />}
          iconClass="bg-brand-50 text-brand-600"
        />
        <MetricCard
          label="今日订单数"
          value={formatInt(summary?.todayOrders ?? 0)}
          hint="本租户 · 实时聚合"
          icon={<ShoppingCart size={20} />}
          iconClass="bg-sky-50 text-sky-600"
        />
        <MetricCard
          label="待出库订单"
          value={formatInt(summary?.readyShipOrders ?? 0)}
          hint="履约 READY · 待一键出库"
          icon={<PackageCheck size={20} />}
          iconClass="bg-emerald-50 text-emerald-600"
        />
        <MetricCard
          label="待支付锁定"
          value={formatInt(summary?.pendingPayOrders ?? 0)}
          hint="超时自动关单 · 库存回补"
          icon={<Timer size={20} />}
          iconClass="bg-amber-50 text-amber-600"
        />
      </div>

      {/* 图表区 */}
      <div className="grid grid-cols-12 gap-4 xl:gap-5">
        <Card
          className="col-span-12 xl:col-span-8"
          icon={<TrendingUp size={16} />}
          title="近 7 日订单 / 营收趋势"
          subtitle="订单量（左轴）与 销售额（右轴）· 实时聚合"
        >
          <TrendChart data={trend} height={290} />
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          icon={<PieChart size={16} />}
          title="商品销量占比"
          subtitle="按销量 TOP 明细汇总 · 实时聚合"
        >
          <SalesShareChart data={salesShare} height={210} />
        </Card>

        {/* 事件流水：仅当显式开启模拟才展示（生产默认隐藏，避免把模拟标为“实时”） */}
        {SIMULATED_REALTIME_FEED ? (
          <Card
            className="col-span-12 md:col-span-6 xl:col-span-12"
            icon={<Radio size={16} />}
            title="事件流水（演示：客户端模拟，非生产实时）"
            subtitle="未接入后端 SSE/WebSocket 事件流前，本卡片仅为交互演示，不代表真实经营流水"
            bodyClassName="p-0"
          >
            <RealtimeFeed baseline={FEED_TPS} onStats={handleFeedStats} />
          </Card>
        ) : null}
      </div>
    </div>
  );
}
