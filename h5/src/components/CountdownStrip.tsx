import { useMemo } from 'react';
import { Flame, Timer } from 'lucide-react';
import { useCountdown } from '../hooks/useCountdown';
import { endOfToday, pad2 } from '../lib/format';

/**
 * 场次截单倒计时条：指向“今日 23:59:59”截止时刻，数字翻牌风 HH:MM:SS。
 * 文案严格对齐需求示例「距离今日助农特惠截单还剩 02:45:18」。
 */
export default function CountdownStrip() {
  const end = useMemo(() => endOfToday(), []);
  const { parts, expired } = useCountdown(end);
  const digits = [parts.hours, parts.minutes, parts.seconds];

  return (
    <section className="mx-auto w-full max-w-[430px] px-4">
      <div className="mt-3 flex items-center justify-between gap-3 rounded-xl bg-gradient-to-r from-orange-500/10 to-amber-400/10 px-3.5 py-2.5 ring-1 ring-orange-200/70">
        <div className="flex min-w-0 items-center gap-2 text-[12px] text-orange-700">
          <span className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-orange-100 text-orange-600">
            <Flame size={15} />
          </span>
          <span className="truncate font-medium">
            {expired ? '本场助农特惠已截单' : '距离今日助农特惠截单还剩'}
          </span>
        </div>

        {!expired ? (
          <div className="flex shrink-0 items-center gap-1">
            {digits.map((d, i) => (
              <span key={i} className="flex items-center gap-1">
                {i > 0 && <span className="text-[11px] font-bold text-orange-300">:</span>}
                <span className="grid h-7 min-w-[26px] place-items-center rounded-md bg-slate-900 px-1 font-digit text-[15px] font-bold tabular-nums text-amber-300 shadow-sm">
                  {pad2(d)}
                </span>
              </span>
            ))}
            <Timer size={13} className="ml-0.5 text-orange-400" />
          </div>
        ) : (
          <span className="shrink-0 text-[12px] font-semibold text-orange-500">明日 00:00 开启</span>
        )}
      </div>
    </section>
  );
}
