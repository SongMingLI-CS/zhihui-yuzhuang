'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Radio, RefreshCw, Wifi, WifiOff } from 'lucide-react';
import { EVENT_STREAM_URL, fetchRecentEvents, toApiError } from '@/lib/http';
import type { EventItem } from '@/lib/types';

/**
 * 真实业务事件流（阶段 D：替换客户端模拟流水）。
 *
 * <p>数据来源为后端 Outbox 真实事件（订单创建/支付/取消，与订单同事务落库），
 * 优先使用 SSE（`GET /api/v1/events/stream`，同源 Cookie 会话），
 * 断线时由 EventSource 自动重连，并同时以 `GET /api/v1/events/recent` 轮询兜底。
 *
 * <p>服务端按已验证主体推导可见租户范围（村委=本租户、政府=授权范围、平台=全域），
 * 前端不做任何数据范围判断。
 */
const STATUS_META: Record<string, { label: string; cls: string }> = {
  PENDING: { label: '待投递', cls: 'text-amber-500' },
  PUBLISHED: { label: '已投递', cls: 'text-emerald-600' },
  PROCESSED: { label: '已消费', cls: 'text-slate-400' },
  FAILED: { label: '投递失败', cls: 'text-red-500' },
};

const MAX_ROWS = 60;

export function LiveEventFeed() {
  const [events, setEvents] = useState<EventItem[]>([]);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cursorRef = useRef<number>(0);
  const seenRef = useRef<Set<number>>(new Set());

  const append = useCallback((items: EventItem[]) => {
    if (items.length === 0) return;
    setEvents((prev) => {
      const merged = [...prev];
      for (const item of items) {
        if (seenRef.current.has(item.id)) continue;
        seenRef.current.add(item.id);
        merged.push(item);
      }
      merged.sort((a, b) => b.id - a.id);
      return merged.slice(0, MAX_ROWS);
    });
  }, []);

  // 首屏 + 轮询兜底（SSE 不可用时仍可用）
  const poll = useCallback(async () => {
    try {
      const items = await fetchRecentEvents(cursorRef.current || null, 30);
      if (items.length > 0) {
        append(items);
        cursorRef.current = Math.max(cursorRef.current, items[items.length - 1].id);
      }
      setError(null);
    } catch (err) {
      setError(toApiError(err).message);
    }
  }, [append]);

  useEffect(() => {
    void poll();
    const timer = window.setInterval(() => void poll(), 15_000);
    return () => window.clearInterval(timer);
  }, [poll]);

  // SSE 实时推送（同源 Cookie 会话自动携带）
  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') return;
    const source = new EventSource(EVENT_STREAM_URL, { withCredentials: true });
    source.addEventListener('open', () => {
      setConnected(true);
      setError(null);
    });
    const onEvent = (e: MessageEvent) => {
      try {
        const item = JSON.parse(e.data) as EventItem;
        append([item]);
        cursorRef.current = Math.max(cursorRef.current, item.id);
      } catch {
        // 忽略无法解析的事件
      }
    };
    source.addEventListener('event', onEvent as EventListener);
    source.onerror = () => setConnected(false);
    return () => source.close();
  }, [append]);

  const recent = events.slice(0, 20);

  return (
    <div className="flex h-full min-h-[220px] flex-col">
      <div className="flex items-center gap-2 border-b border-slate-100 px-3 py-2 text-[11px] text-slate-500">
        {connected ? (
          <>
            <Wifi size={12} className="text-emerald-500" />
            <span className="text-emerald-600">SSE 已连接（真实事件）</span>
          </>
        ) : (
          <>
            <WifiOff size={12} className="text-amber-500" />
            <span>SSE 未连接，使用 15s 轮询兜底</span>
          </>
        )}
        <button
          type="button"
          onClick={() => void poll()}
          className="ml-auto inline-flex h-6 items-center gap-1 rounded-md border border-slate-200 px-2 text-[10px] text-slate-600 hover:border-brand-300"
        >
          <RefreshCw size={10} /> 刷新
        </button>
      </div>

      {error && <p className="px-3 pt-2 text-[11px] text-red-500">{error}</p>}

      {recent.length === 0 ? (
        <div className="flex flex-1 items-center justify-center gap-1.5 py-8 text-[11px] text-slate-400">
          <Radio size={12} />
          暂无业务事件（下单/支付/取消后此处会出现真实事件）
        </div>
      ) : (
        <ul className="flex-1 divide-y divide-slate-100 overflow-y-auto">
          {recent.map((e) => {
            const meta = STATUS_META[e.status] ?? { label: e.status, cls: 'text-slate-400' };
            return (
              <li key={e.id} className="flex items-start gap-2 px-3 py-2">
                <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-400" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[12px] text-slate-700">{e.summary}</span>
                  <span className="mt-0.5 block text-[10px] text-slate-400">
                    #{e.id} · {e.tenantId} · {e.createdAt ? e.createdAt.replace('T', ' ').slice(0, 19) : '—'}
                  </span>
                </span>
                <span className={`shrink-0 text-[10px] ${meta.cls}`}>{meta.label}</span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
