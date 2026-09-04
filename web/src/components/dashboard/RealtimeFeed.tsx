'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Pause, Play, Radio, RotateCcw } from 'lucide-react';
import { cn } from '@/lib/cn';
import {
  CONSUMER_BACKEND,
  GROUP_ORDER_DISPATCH,
  STREAM_ORDER_EVENTS,
} from '@/lib/config';
import type { StreamEvent, StreamLevel } from '@/lib/demo';
import { formatTime } from '@/lib/format';

/* ===================== 实时流水（Redis Streams 异步消费语义 · 客户端模拟） =====================
 * 说明：无 SSE/WS 推送端点（backend 未暴露流订阅），此处按 Redis Streams 的
 * XADD / XREADGROUP / XPENDING / XACK 消费语义在浏览器端生成拟真操作日志，
 * 字段常量与 backend RedisStreamConstants.java 对齐。
 */

export interface FeedStats {
  total: number;
  perMin: number;
}

interface RealtimeFeedProps {
  maxLines?: number;
  speedMs?: number;
  /** 初始稳态吞吐基线（条/分），用于 EMA 起步，避免首屏从 0 突兀跳变 */
  baseline?: number;
  onStats?: (stats: FeedStats) => void;
}

const LEVEL_META: Record<StreamLevel, { tag: string; cls: string; dot: string }> = {
  outbox: { tag: 'OUTBOX', cls: 'bg-amber-50 text-amber-700', dot: 'bg-amber-400' },
  stream: { tag: 'STREAM', cls: 'bg-sky-50 text-sky-700', dot: 'bg-sky-400' },
  pick: { tag: '拣货', cls: 'bg-violet-50 text-violet-700', dot: 'bg-violet-400' },
  ready: { tag: '就绪', cls: 'bg-brand-50 text-brand-700', dot: 'bg-brand-500' },
  outbound: { tag: '出库', cls: 'bg-emerald-50 text-emerald-700', dot: 'bg-emerald-400' },
  error: { tag: '异常', cls: 'bg-red-50 text-red-600', dot: 'bg-red-400' },
  info: { tag: '系统', cls: 'bg-slate-100 text-slate-500', dot: 'bg-slate-400' },
};

const PRODUCTS = ['于庄小磨香油', '于庄富硒小麦粉', '于庄荆条土蜂蜜'];

let ordSeq = 0;
function nextOrderNo(now: Date): string {
  ordSeq += 1;
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `ORD${y}${m}${d}${(800 + ordSeq).toString()}`;
}

const ri = (max: number) => Math.floor(Math.random() * max);
const pick = <T,>(arr: readonly T[]): T => arr[ri(arr.length)];

function streamMsgId(seq: number): string {
  return `${Date.now().toString(16)}-${seq}`;
}

/* 带权重的操作日志模板池（level → 文案构建器） */
interface TemplateDef {
  level: StreamLevel;
  w: number;
  build: (now: Date, seq: number) => string;
}

const TEMPLATES: TemplateDef[] = [
  {
    level: 'outbox',
    w: 20,
    build: () => `OutboxSweeper 扫描事务出表：${1 + ri(3)} 条订单事件待投递 → ${STREAM_ORDER_EVENTS}`,
  },
  {
    level: 'outbox',
    w: 14,
    build: (n) => `事务出表投递成功：${nextOrderNo(n)} 已确认入库 Outbox，等待削峰异步发送`,
  },
  {
    level: 'stream',
    w: 18,
    build: (n, seq) => `XADD ${STREAM_ORDER_EVENTS} * orderId=${nextOrderNo(n)} source=${pick(['H5_PRIVATE', 'DOUYIN', 'KUAISHOU', 'B2B_PORTAL'])} id=${streamMsgId(seq)}`,
  },
  {
    level: 'stream',
    w: 15,
    build: () => `XREADGROUP GROUP ${GROUP_ORDER_DISPATCH} ${CONSUMER_BACKEND} 拉取 ${1 + ri(3)} 条待消费消息`,
  },
  {
    level: 'pick',
    w: 16,
    build: (n) => `拣货派单：${nextOrderNo(n)} · ${pick(PRODUCTS)}×${1 + ri(9)} → 拣货台-${String(1 + ri(8)).padStart(2, '0')}`,
  },
  {
    level: 'ready',
    w: 14,
    build: (n) => `${nextOrderNo(n)} 拣货校验通过，出库单生成，状态置为 READY（出库就绪）`,
  },
  {
    level: 'outbound',
    w: 12,
    build: (n) => `${nextOrderNo(n)} 已装车出库，库存扣减完成，回调全渠道销账`,
  },
  {
    level: 'outbound',
    w: 8,
    build: (n) => `运单 SF${202600000 + ri(9000000)} 关联 ${nextOrderNo(n)} 出库完成`,
  },
  {
    level: 'error',
    w: 7,
    build: (n) => `消费异常：${nextOrderNo(n)} 幂等校验冲突，第 ${1 + ri(2)} 次重投（延时 2s）`,
  },
  {
    level: 'error',
    w: 5,
    build: () => `XPENDING ${STREAM_ORDER_EVENTS} 检出 ${1 + ri(4)} 条未确认消息，触发补偿重投`,
  },
  {
    level: 'info',
    w: 9,
    build: () => `${CONSUMER_BACKEND} 心跳保活 OK（${ri(3)} 条积压待处理）`,
  },
  {
    level: 'info',
    w: 7,
    build: (n, seq) => `XACK ${STREAM_ORDER_EVENTS} ${streamMsgId(seq)} 消费确认完成`,
  },
];

const WEIGHT_SUM = TEMPLATES.reduce((s, t) => s + t.w, 0);

function randomTemplate(now: Date, seq: number): { level: StreamLevel; text: string } {
  let r = Math.random() * WEIGHT_SUM;
  for (const t of TEMPLATES) {
    r -= t.w;
    if (r <= 0) return { level: t.level, text: t.build(now, seq) };
  }
  const last = TEMPLATES[TEMPLATES.length - 1];
  return { level: last.level, text: last.build(now, seq) };
}

function makeEvent(now: Date, seq: number): StreamEvent {
  const { level, text } = randomTemplate(now, seq);
  return { id: `${now.getTime()}-${seq}`, time: formatTime(now), level, text };
}

function Backfill() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-slate-400">
      <Radio size={22} className="animate-blink text-brand-500" />
      <p className="text-xs">正在订阅 stream:order:events 异步消费事件流…</p>
    </div>
  );
}

export function RealtimeFeed({
  maxLines = 220,
  speedMs = 1100,
  baseline = 148,
  onStats,
}: RealtimeFeedProps) {
  const [events, setEvents] = useState<StreamEvent[]>([]);
  const [paused, setPaused] = useState(false);
  const [stat, setStat] = useState<FeedStats>({ total: 0, perMin: baseline });

  const seqRef = useRef(0);
  const totalRef = useRef(0);
  const emaRef = useRef(baseline);
  const pausedRef = useRef(false);
  const onStatsRef = useRef(onStats);
  const bodyRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  useEffect(() => {
    onStatsRef.current = onStats;
  }, [onStats]);

  const emitBatch = useCallback(
    (now: Date, batch: number) => {
      const items: StreamEvent[] = [];
      for (let i = 0; i < batch; i += 1) {
        seqRef.current += 1;
        items.push(makeEvent(now, seqRef.current));
      }
      setEvents((prev) => [...prev, ...items].slice(-maxLines));

      // 统计：EMA 平滑瞬时吞吐
      totalRef.current += batch;
      const instant = batch / (speedMs / 60_000);
      emaRef.current = emaRef.current * 0.82 + instant * 0.18;
      const next: FeedStats = { total: totalRef.current, perMin: Math.round(emaRef.current) };
      setStat(next);
      onStatsRef.current?.(next);
    },
    [maxLines, speedMs],
  );

  // 挂载：补一段“历史”日志 + 启动订阅定时器
  useEffect(() => {
    const now = new Date();
    const seed: StreamEvent[] = [];
    for (let i = 8; i >= 1; i -= 1) {
      const d = new Date(now.getTime() - i * 2600);
      seed.push(makeEvent(d, seqRef.current + (9 - i)));
    }
    seqRef.current += 8;
    setEvents(seed);

    const timer = window.setInterval(() => {
      if (pausedRef.current) return;
      const batch = Math.random() < 0.28 ? 3 : Math.random() < 0.6 ? 2 : 1;
      emitBatch(new Date(), batch);
    }, speedMs);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 追踪最新：接近底部时自动跟随滚动
  useEffect(() => {
    const el = bodyRef.current;
    if (el && atBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [events]);

  const onBodyScroll = () => {
    const el = bodyRef.current;
    if (!el) return;
    atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 72;
  };

  const clearFeed = () => setEvents([]);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 工具栏 */}
      <div className="flex items-center gap-3 border-b border-slate-100 px-4 py-2.5">
        <span className="inline-flex items-center gap-1.5 text-[11px] font-medium text-slate-500">
          <span className="h-1.5 w-1.5 animate-blink rounded-full bg-emerald-400" />
          实时削峰
        </span>
        <span className="num text-sm font-bold text-brand-700">{stat.perMin}</span>
        <span className="text-[11px] text-slate-400">条/分</span>
        <span className="text-slate-300">·</span>
        <span className="num text-[11px] text-slate-400">累计 {stat.total} 条</span>
        <div className="ml-auto flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setPaused((v) => !v)}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-[11px] font-medium text-slate-600 transition hover:border-brand-300 hover:text-brand-700"
          >
            {paused ? <Play size={12} /> : <Pause size={12} />}
            {paused ? '继续' : '暂停'}
          </button>
          <button
            type="button"
            onClick={clearFeed}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-[11px] font-medium text-slate-600 transition hover:border-red-200 hover:text-red-500"
          >
            <RotateCcw size={12} />
            清屏
          </button>
        </div>
      </div>

      {/* 事件流 */}
      {events.length === 0 ? (
        <div className="h-[320px]">
          <Backfill />
        </div>
      ) : (
        <div
          ref={bodyRef}
          onScroll={onBodyScroll}
          className="scrollbar-thin h-[320px] flex-1 overflow-y-auto px-2 py-1"
        >
          {events.map((e, idx) => {
            const meta = LEVEL_META[e.level];
            return (
              <div
                key={e.id}
                className={cn(
                  'flex items-start gap-2.5 rounded-lg px-2 py-[5px] transition-colors hover:bg-slate-50',
                  idx === events.length - 1 && 'animate-fade-in bg-brand-50/40',
                )}
              >
                <span className="num w-[72px] shrink-0 pt-px text-[11px] text-slate-400">{e.time}</span>
                <span
                  className={cn(
                    'mt-px inline-flex w-[68px] shrink-0 items-center gap-1 rounded px-1.5 py-px text-[10px] font-semibold',
                    meta.cls,
                  )}
                >
                  <span className={cn('h-1 w-1 rounded-full', meta.dot)} />
                  {meta.tag}
                </span>
                <span className="min-w-0 flex-1 break-words pt-px text-xs leading-5 text-slate-600">
                  {e.text}
                </span>
              </div>
            );
          })}
        </div>
      )}

      {/* 底部常量说明 */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-slate-100 px-4 py-2 text-[10px] text-slate-400">
        <span className="inline-flex items-center gap-1">
          <span className="rounded bg-slate-100 px-1 py-px font-mono">{STREAM_ORDER_EVENTS}</span>
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="rounded bg-slate-100 px-1 py-px font-mono">{GROUP_ORDER_DISPATCH}</span>
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="rounded bg-slate-100 px-1 py-px font-mono">{CONSUMER_BACKEND}</span>
        </span>
        <span className="ml-auto text-slate-300">消费组语义模拟 · 与后端常量对齐</span>
      </div>
    </div>
  );
}
