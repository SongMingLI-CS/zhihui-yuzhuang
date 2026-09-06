'use client';

import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { Building2, Check, ChevronDown, Clock, LogIn, LogOut, MapPin, Menu, UserRound, Wifi } from 'lucide-react';
import { cn } from '@/lib/cn';
import { APP_TITLE } from '@/lib/config';
import { getTenant, setTenant, subscribeTenant, TENANT_OPTIONS, type TenantInfo } from '@/lib/tenant';
import { useLinkHealth, type LinkHealth } from '@/lib/useLinkHealth';
import { formatTime } from '@/lib/format';
import { getStoredUser, persistUser, setCurrentUser, subscribeAuth } from '@/lib/auth';
import { setAccessToken } from '@/lib/http';
import type { UserInfo } from '@/lib/types';
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

export function TopBar({ onMenuOpen, menuButtonRef }: { onMenuOpen: () => void; menuButtonRef: RefObject<HTMLButtonElement> }) {
  const pathname = usePathname();
  const health = useLinkHealth();
  const [tenant, setTenantState] = useState<TenantInfo>(() => getTenant());
  const [pickerOpen, setPickerOpen] = useState(false);
  const [now, setNow] = useState<Date | null>(null);
  const [user, setUser] = useState<UserInfo | null>(null);
  const router = useRouter();
  const pickerRef = useRef<HTMLDivElement>(null);

  // 订阅租户外部存储变化（axios 拦截器同步读取）
  useEffect(() => subscribeTenant((t) => setTenantState(t)), []);

  // 认证态：初始化并订阅（顶栏用户区 / 登录页共享）
  useEffect(() => {
    setUser(getStoredUser());
    return subscribeAuth((u) => setUser(u));
  }, []);

  // 时钟
  useEffect(() => {
    setNow(new Date());
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
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPickerOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [pickerOpen]);

  const active = useMemo(() => {
    const item = NAV_ITEMS.find((n) => n.href === pathname);
    return item ?? NAV_ITEMS[0];
  }, [pathname]);

  const chooseTenant = (t: TenantInfo) => {
    setTenant(t);
    setPickerOpen(false);
  };

  const logout = () => {
    setAccessToken(null);
    persistUser(null);
    setCurrentUser(null);
    router.replace('/login');
  };

  return (
    <header className="z-20 flex h-16 shrink-0 items-center gap-2 border-b border-slate-200/70 bg-white/88 px-3 backdrop-blur-xl sm:gap-3 sm:px-4 lg:px-6">
      <button
        ref={menuButtonRef}
        type="button"
        onClick={onMenuOpen}
        className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-slate-200 bg-white text-slate-600 shadow-sm transition hover:border-brand-200 hover:bg-brand-50 hover:text-brand-700 lg:hidden"
        aria-label="打开主导航"
      >
        <Menu size={19} />
      </button>
      {/* 左侧标题区 */}
      <div className="min-w-0 flex-1">
        <h1 className="truncate text-sm font-bold tracking-[-0.01em] text-slate-900 sm:text-[15px]">
          <span className="hidden sm:inline">{APP_TITLE}</span>
          <span className="sm:hidden">{active.short}</span>
        </h1>
        <p className="hidden truncate text-[11px] text-slate-400 sm:block">{active.description}</p>
      </div>

      {/* 租户选择器 */}
      <div ref={pickerRef} className="relative">
        <button
          type="button"
          onClick={() => setPickerOpen((v) => !v)}
          className="flex h-10 items-center gap-2 rounded-xl border border-slate-200 bg-white px-2.5 text-left shadow-sm transition hover:border-brand-300 sm:px-3"
          aria-expanded={pickerOpen}
          aria-haspopup="listbox"
          aria-label={`切换运营租户，当前：${tenant.name}`}
        >
          <span className="grid h-6 w-6 place-items-center rounded-lg bg-brand-50 text-brand-600">
            <Building2 size={14} />
          </span>
          <span className="hidden max-w-[240px] truncate text-xs font-semibold text-slate-700 xl:block">
            {tenant.name}
          </span>
          <ChevronDown size={14} className={cn('text-slate-400 transition', pickerOpen && 'rotate-180')} />
        </button>

        {pickerOpen && (
          <div role="listbox" aria-label="运营租户" className="absolute right-0 top-[calc(100%+8px)] w-[min(88vw,320px)] animate-fade-in rounded-2xl border border-slate-200 bg-white p-2 shadow-[var(--shadow-float)]">
            <p className="px-3 py-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">切换运营租户</p>
            {TENANT_OPTIONS.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => chooseTenant(t)}
                role="option"
                aria-selected={t.id === tenant.id}
                className="flex min-h-12 w-full items-start gap-2 rounded-xl px-3 py-2.5 text-left transition hover:bg-brand-50"
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
      <div className="hidden h-10 items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-3 md:flex">
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
      <div className="hidden h-10 items-center gap-2 rounded-xl bg-brand-900 px-3 text-brand-100 2xl:flex">
        <Clock size={14} className="text-gold-400" />
        <span className="num min-w-[62px] text-[13px] tabular-nums text-white">{now ? formatTime(now) : '--:--:--'}</span>
      </div>

      {/* 用户区 */}
      {user ? (
        <div className="flex h-10 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-2 shadow-sm">
          <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600">
            <UserRound size={14} />
          </span>
          <span className="hidden max-w-[140px] truncate text-xs font-semibold text-slate-700 xl:block">
            {user.displayName}
          </span>
          <button
            type="button"
            onClick={logout}
            className="flex h-7 items-center gap-1 rounded-lg px-2 text-[11px] font-medium text-slate-500 transition hover:bg-red-50 hover:text-red-600"
            aria-label="退出登录"
          >
            <LogOut size={13} />
            <span className="hidden lg:inline">退出</span>
          </button>
        </div>
      ) : (
        <Link
          href="/login"
          className="flex h-10 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 text-xs font-semibold text-slate-700 shadow-sm transition hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700"
        >
          <LogIn size={14} />
          <span className="hidden sm:inline">登录</span>
        </Link>
      )}
      <span className="grid h-10 w-10 place-items-center rounded-xl bg-brand-50 text-brand-700 md:hidden" title="服务链路状态">
        <Wifi size={17} />
      </span>
    </header>
  );
}
