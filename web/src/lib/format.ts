/** 数值 / 时间格式化工具 */

/** 万元制金额：123456789 -> 1.23亿 / 12864300 -> 1286.4万 */
export function formatYuan(n: number): string {
  if (n >= 100_000_000) {
    return `¥${(n / 100_000_000).toFixed(2)}亿`;
  }
  if (n >= 10_000) {
    return `¥${(n / 10_000).toFixed(1)}万`;
  }
  return `¥${n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`;
}

/** 精确金额：¥1,286,430.00 */
export function formatCNY(n: number): string {
  return `¥${n.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

/** 整数金额（元）千分位 */
export function formatInt(n: number): string {
  return n.toLocaleString('zh-CN');
}

/** 百分比（0~1 或 0~100 均可归一） */
export function formatPct(v: number): string {
  return `${(Math.round(v * 1000) / 10).toFixed(1)}%`;
}

/** 两位小数 */
export function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** 本地时间 HH:mm:ss */
export function formatTime(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 本地时间 HH:mm */
export function formatTimeShort(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 本地日期 YYYY-MM-DD */
export function formatDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 本地日期 MM-DD（大屏坐标轴短标签） */
export function formatDateShort(d: Date): string {
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 近 n 日短标签（含今日，升序） */
export function lastNDays(n: number): string[] {
  const out: string[] = [];
  const now = new Date();
  for (let i = n - 1; i >= 0; i -= 1) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    out.push(formatDateShort(d));
  }
  return out;
}

let requestSeq = 0;

/** 生成全局递增/随机 requestId（用于演示请求追踪） */
export function newDemoRequestId(prefix = 'req'): string {
  requestSeq += 1;
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Date.now().toString(36)}-${requestSeq}`;
}
