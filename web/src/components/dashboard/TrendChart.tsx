'use client';

import { useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { graphic, type EChartsOption } from 'echarts';
import type { TrendPoint } from '@/lib/demo';

interface TrendChartProps {
  data: TrendPoint[];
  height?: number;
}

const G = { left: 8, right: 8, top: 40, bottom: 4, containLabel: true };

function areaGradient(from: string) {
  // 使用 echarts.graphic.LinearGradient 构造渐变，避免对象字面量 readonly 类型不满足 AreaStyleOption
  return new graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: from },
    { offset: 1, color: 'rgba(255,255,255,0)' },
  ]);
}

/** 图表 A：近 7 日订单与营收增长趋势（ECharts 折线面积图） */
export function TrendChart({ data, height = 280 }: TrendChartProps) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const option = useMemo<EChartsOption>(
    () => ({
      animationDuration: 700,
      color: ['#2c724a', '#d9a52e'],
      grid: G,
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(15,23,42,0.92)',
        borderWidth: 0,
        textStyle: { color: '#f8fafc', fontSize: 12 },
        axisPointer: { type: 'cross', crossStyle: { color: '#94a3b8' } },
        formatter: (params: unknown) => {
          const list = Array.isArray(params) ? params : [params];
          const rows = (list as Array<{ marker: string; seriesName: string; value: number }>)
            .map((p) => {
              const v = p.value;
              const display =
                p.seriesName === '营收' ? `¥${Number(v).toLocaleString('zh-CN')}` : `${v} 单`;
              return `${p.marker}${p.seriesName}：<b>${display}</b>`;
            })
            .join('<br/>');
          return rows;
        },
      },
      legend: {
        top: 0,
        right: 0,
        icon: 'circle',
        itemWidth: 9,
        itemHeight: 9,
        textStyle: { color: '#64748b', fontSize: 12 },
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: data.map((d) => d.label),
        axisLine: { lineStyle: { color: '#e2e8f0' } },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8' },
      },
      yAxis: [
        {
          type: 'value',
          name: '订单(单)',
          nameTextStyle: { color: '#cbd5e1', fontSize: 10, padding: [0, 0, 0, -8] },
          minInterval: 1,
          splitLine: { lineStyle: { color: '#f1f5f9' } },
          axisLabel: { color: '#94a3b8' },
        },
        {
          type: 'value',
          name: '营收(元)',
          nameTextStyle: { color: '#cbd5e1', fontSize: 10, padding: [0, -6, 0, 0] },
          splitLine: { show: false },
          axisLabel: {
            color: '#94a3b8',
            formatter: (v: unknown) => {
              const n = Number(v);
              return n >= 1000 ? `${n / 1000}k` : `${n}`;
            },
          },
        },
      ],
      series: [
        {
          name: '订单数',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          yAxisIndex: 0,
          data: data.map((d) => d.orders),
          lineStyle: { width: 3, color: '#2c724a' },
          itemStyle: { color: '#2c724a' },
          areaStyle: { color: areaGradient('rgba(44,114,74,0.26)') },
        },
        {
          name: '营收',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          yAxisIndex: 1,
          data: data.map((d) => d.revenue),
          lineStyle: { width: 3, color: '#d9a52e' },
          itemStyle: { color: '#d9a52e' },
          areaStyle: { color: areaGradient('rgba(217,165,46,0.22)') },
        },
      ],
    }),
    [data],
  );

  if (!mounted) {
    return <div style={{ height }} className="animate-pulse rounded-xl bg-slate-100" />;
  }
  return <ReactECharts option={option} notMerge lazyUpdate style={{ height }} />;
}
