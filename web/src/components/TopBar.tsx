'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { Building2, Check, ChevronDown, Clock, MapPin } from 'lucide-react';
import { cn } from '@/lib/cn';
import { APP_TITLE } from '@/lib/config';
import { getTenant, setTenant, subscribeTenant, TENANT_OPTIONS, type TenantInfo } from '@/lib/tenant';
import { useLinkHealth, type LinkHealth } from '@/lib/useLinkHealth';
import { formatTime } from '@/lib/format';
import { NAV_ITEMS } from './navItems';

const LINK_META: Array<{ key: keyof LinkHealth; label: string }> = [
  { key: 'gateway', label: '网关' },
  { key: 'backend', label: '业务后端' },
  { key: 'ai', label: 'AI 服务' },
];

function Dot({ state }: { state: string }) {
  const cls =
    state === 'ok'
      ? 'bg-emerald-400'
      : state === 'down'
        ? 'bg-red-400'
        : 'bg-amber-300 animate-blink';
  return <span className={cn('h-1.5 w-1.5 rounded-full', cls)} />;
}

export function TopBar() {
  const pathname = usePathname();
  const health = useLinkHealth();
  const [tenant, setTenantState] = useState<TenantInfo>(() => getTenant());
  const [pickerOpen, setPickerOpen] = useState(false);
  const [now, setNow] = useState<Date>(() => new Date());
  const pickerRef = useRef<HTMLDivElement>(null);

  // 订阅租户外部存储变化（axios 拦截器同步读取）
  useEffect(() => subscribeTenant((t) => setTenantState(t)), []);

  // 时钟
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // 点击外部关闭租户选择器
  useEffect(() => {
    if (!pickerOpen) return;
    const onClick = (e: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) {
        setPickerOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [pickerOpen]);

  const active = useMemo(() => {
    const item = NAV_ITEMS.find((n) => n.href === pathname);
    return item ?? NAV_ITEMS[0];
  }, [pathname]);

  const chooseTenant = (t: TenantInfo) => {
    setTenant(t);
    setPickerOpen(false);
  };

  return (
    <header className="z-20 flex h-[60px] shrink-0 items-center gap-4 border-b border-slate-200/80 bg-white/90 px-6 backdrop-blur">
      {/* 左侧标题区 */}
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-[15px] font-bold text-slate-800">{APP_TITLE}</h1>
        <p className="truncate text-[11px] text-slate-400">{active.description}</p>
      </div>

      {/* 租户选择器 */}
      <div ref={pickerRef} className="relative">
        <button
          type="button"
          onClick={() => setPickerOpen((v) => !v)}
          className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2 text-left hover:border-brand-300"
        >
          <span className="grid h-6 w-6 place-items-center rounded-lg bg-brand-50 text-brand-600">
            <Building2 size={14} />
          </span>
          <span className="hidden max-w-[240px] truncate text-xs font-semibold text-slate-700 lg:block">
            {tenant.name}
          </span>
          <ChevronDown size={14} className={cn('text-slate-400 transition', pickerOpen && 'rotate-180')} />
        </button>

        {pickerOpen && (
          <div className="absolute right-0 top-[calc(100%+6px)] w-[300px] animate-fade-in rounded-2xl border border-slate-200 bg-white p-1.5 shadow-xl shadow-slate-900/10">
            <p className="px-3 py-1.5 text-[11px] font-medium text-slate-400">切换运营租户（X-Tenant-Id）</p>
            {TENANT_OPTIONS.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => chooseTenant(t)}
                className="flex w-full items-start gap-2 rounded-xl px-3 py-2 text-left hover:bg-brand-50"
              >
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-1 text-xs font-semibold text-slate-700">
                    <span className="truncate">{t.name}</span>
                    {t.id === tenant.id && <Check size={13} className="shrink-0 text-brand-600" />}
                  </span>
                  <span className="mt-0.5 flex items-center gap-1 text-[10px] text-slate-400">
                    <MapPin size={10} />
                    {t.region} · {t.id}
                  </span>
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* 链路状态指示器 */}
      <div className="hidden items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 md:flex">
        <span className="text-[10px] font-medium uppercase tracking-wide text-slate-400">链路</span>
        <div className="flex items-center gap-3">
          {LINK_META.map(({ key, label }) => (
            <span key={key} className="flex items-center gap-1.5 text-[11px] text-slate-600">
              <Dot state={health[key]} />
              {label}
            </span>
          ))}
        </div>
      </div>

      {/* 时钟 */}
      <div className="hidden items-center gap-2 rounded-xl bg-brand-900 px-3 py-2 text-brand-100 xl:flex">
        <Clock size={14} className="text-gold-400" />
        <span className="num text-[13px] tabular-nums text-white">{formatTime(now)}</span>
      </div>
    </header>
  );
}
