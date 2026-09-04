import { useEffect, useState } from 'react';
import { toClock, type ClockParts } from '../lib/format';

interface CountdownState {
  /** 剩余毫秒 */
  remainingMs: number;
  /** 时:分:秒 */
  parts: ClockParts;
  /** 是否已到截止 */
  expired: boolean;
}

/**
 * 面向截止时刻（毫秒时间戳）的倒计时，每秒刷新一次。
 * targetMs 为 null 时静止在 00:00:00。
 */
export function useCountdown(targetMs: number | null): CountdownState {
  const [now, setNow] = useState<number>(() => Date.now());

  useEffect(() => {
    if (targetMs == null) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [targetMs]);

  const remainingMs = targetMs == null ? 0 : Math.max(0, targetMs - now);
  return {
    remainingMs,
    parts: toClock(remainingMs),
    expired: targetMs != null && remainingMs <= 0,
  };
}
