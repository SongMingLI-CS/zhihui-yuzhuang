'use client';

import { useEffect, useMemo, useState } from 'react';
import ReactEChartsCore from 'echarts-for-react/lib/core';
import type { EChartsOption } from 'echarts';
import type { SalesShareItem } from '@/lib/demo';
import { formatYuan } from '@/lib/format';
import echarts from '@/lib/charts';

/** 图表 B：于庄特色产品销售占比（ECharts 环形图 + 右侧明细） */
export function SalesShareChart({ data, height = 220 }: { data: SalesShareItem[]; height?: number }) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const total = useMemo(() => data.reduce((s, d) => s + d.amount, 0), [data]);

  const option = useMemo<EChartsOption>(
    () => ({
      animationDuration: 700,
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(15,23,42,0.92)',
        borderWidth: 0,
        textStyle: { color: '#f8fafc', fontSize: 12 },
        formatter: (params: unknown) => {
          const p = params as { name: string; percent: number };
          return `${p.name}<br/><b>${Number(p.percent).toFixed(1)}%</b>`;
        },
      },
      series: [
        {
          name: '销售占比',
          type: 'pie',
          radius: ['56%', '78%'],
          center: ['50%', '52%'],
          avoidLabelOverlap: true,
          padAngle: 2,
          itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 2 },
          label: { show: false },
          emphasis: { label: { show: false }, scaleSize: 6 },
          data: data.map((d) => ({ name: d.name, value: d.value, itemStyle: { color: d.color } })),
        },
      ],
    }),
    [data],
  );

  return (
    <div className="flex flex-col items-center gap-2 sm:flex-row sm:gap-4">
      <div className="relative w-full shrink-0 sm:w-[46%]">
        {mounted ? (
          <ReactEChartsCore echarts={echarts} option={option} notMerge lazyUpdate style={{ height }} />
        ) : (
          <div style={{ height }} className="animate-pulse rounded-xl bg-slate-100" />
        )}
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center pb-2 text-center">
          <span className="text-[10px] text-slate-400">助农销售额</span>
          <span className="num text-sm font-bold text-slate-800">{formatYuan(total)}</span>
        </div>
      </div>
      <ul className="w-full min-w-0 flex-1 space-y-3">
        {data.map((d) => (
          <li key={d.name}>
            <div className="flex items-center justify-between gap-2 text-xs">
              <span className="flex min-w-0 items-center gap-1.5 text-slate-600">
                <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: d.color }} />
                <span className="truncate">{d.name}</span>
              </span>
              <span className="num font-semibold text-slate-800">{d.value}%</span>
            </div>
            <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100">
              <div
                className="h-full rounded-full transition-all"
                style={{ width: `${d.value}%`, background: d.color }}
              />
            </div>
            <p className="num mt-1 text-right text-[11px] text-slate-400">{formatYuan(d.amount)}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}
