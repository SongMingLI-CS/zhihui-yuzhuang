'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  Activity,
  Info,
  PieChart,
  Radio,
  ShoppingCart,
  Sparkles,
  TrendingUp,
  Wallet,
} from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { MetricCard } from '@/components/dashboard/MetricCard';
import { TrendChart } from '@/components/dashboard/TrendChart';
import { SalesShareChart } from '@/components/dashboard/SalesShareChart';
import { RealtimeFeed, type FeedStats } from '@/components/dashboard/RealtimeFeed';
import { METRICS, SALES_SHARE, buildTrend } from '@/lib/demo';
import { formatDate, formatInt, formatYuan } from '@/lib/format';

export default function DashboardPage() {
  const [today, setToday] = useState<Date | null>(null);
  const [liveStats, setLiveStats] = useState<FeedStats>({
    total: 0,
    perMin: METRICS.outboxTps,
  });
  // 趋势数据为静态演示快照，缓存避免实时流水每秒触发整个页面时重建数组、重绘图表
  const trend = useMemo(() => buildTrend(7), []);

  useEffect(() => setToday(new Date()), []);

  const onFeedStats = (stats: FeedStats) => setLiveStats(stats);

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="OPERATIONS OVERVIEW"
        title="产业治理大盘"
        description={`于庄合作社今日经营快照 · ${today ? formatDate(today) : '今日'}`}
        actions={<Badge tone="amber"><Info size={12} />演示数据</Badge>}
      />

      {/* 指标卡 */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:gap-4 2xl:grid-cols-4">
        <MetricCard
          label="累计助农销售额"
          value={formatYuan(METRICS.cumulativeSales)}
          delta={12.4}
          deltaSuffix="%"
          hint="较年初 · 覆盖 3 大特产 SKU"
          icon={<Wallet size={20} />}
          iconClass="bg-brand-50 text-brand-600"
        />
        <MetricCard
          label="今日订单数"
          value={formatInt(METRICS.todayOrders)}
          delta={8.6}
          deltaSuffix="%"
          hint="全渠道聚合 · 含 B2B 集采"
          icon={<ShoppingCart size={20} />}
          iconClass="bg-sky-50 text-sky-600"
        />
        <MetricCard
          label="农技 RAG 服务人次"
          value={formatInt(METRICS.ragCalls)}
          delta={23.1}
          deltaSuffix="%"
          hint="知识库检索 + DeepSeek 生成"
          icon={<Sparkles size={20} />}
          iconClass="bg-violet-50 text-violet-600"
        />
        <MetricCard
          label="Outbox 削峰吞吐"
          value={`${liveStats.perMin} 条/分`}
          hint="实时削峰窗口 · 异步消费"
          icon={<Activity size={20} />}
          iconClass="bg-amber-50 text-amber-600"
        />
      </div>

      {/* 图表区 */}
      <div className="grid grid-cols-12 gap-4 xl:gap-5">
        <Card
          className="col-span-12 xl:col-span-8"
          icon={<TrendingUp size={16} />}
          title="近 7 日订单 / 营收趋势"
          subtitle="订单量（左轴）与 销售额（右轴）· 演示快照"
        >
          <TrendChart data={trend} height={290} />
        </Card>

        <Card
          className="col-span-12 md:col-span-6 xl:col-span-4"
          icon={<PieChart size={16} />}
          title="销售占比 · 于庄三宝"
          subtitle="按销售额口径 · 演示快照"
        >
          <SalesShareChart data={SALES_SHARE} height={210} />
        </Card>

        {/* 实时流水（Redis Streams 异步消费） */}
        <Card
          className="col-span-12 md:col-span-6 xl:col-span-12"
          icon={<Radio size={16} />}
          title="Redis Streams · 订单异步消费实时流水"
          subtitle="出库就绪 / 拣货派单 / 削峰写流动态滚动 · 客户端语义模拟"
          bodyClassName="p-0"
        >
          <RealtimeFeed baseline={METRICS.outboxTps} onStats={onFeedStats} />
        </Card>
      </div>
    </div>
  );
}
