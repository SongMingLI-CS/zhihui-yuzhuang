/** 金额格式化：68 -> "¥68.00" */
export function formatPrice(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? Number(value) : Number(value ?? 0);
  if (Number.isNaN(n)) return '¥0.00';
  return `¥${n.toFixed(2)}`;
}

/** 数字补零（≥2 位，小时允许超过两位） */
export function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

export interface ClockParts {
  hours: number;
  minutes: number;
  seconds: number;
}

/** 将毫秒差转成 时:分:秒 展示 */
export function toClock(totalMs: number): ClockParts {
  const totalSeconds = Math.max(0, Math.floor(totalMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return { hours, minutes, seconds };
}

export function formatClock(parts: ClockParts): string {
  return `${pad2(parts.hours)}:${pad2(parts.minutes)}:${pad2(parts.seconds)}`;
}

/** 今日 23:59:59.999 截单时刻（毫秒） */
export function endOfToday(): number {
  const d = new Date();
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

/** 千分位（用于较大库存） */
export function formatStock(n: number): string {
  return n.toLocaleString('zh-CN');
}
