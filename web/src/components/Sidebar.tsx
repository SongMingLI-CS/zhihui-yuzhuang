'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ArrowUpRight, Wheat } from 'lucide-react';
import { cn } from '@/lib/cn';
import { APP_SHORT } from '@/lib/config';
import { NAV_ITEMS } from './navItems';

export function Sidebar({ mobile = false, onNavigate }: { mobile?: boolean; onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <aside data-mobile={mobile || undefined} className="flex h-dvh w-[244px] shrink-0 flex-col bg-[#163b2a] text-brand-100 shadow-[12px_0_36px_rgba(16,45,31,.08)]">
      {/* 品牌区 */}
      <div className="flex items-center gap-3 px-5 pb-6 pt-5">
        <span className="grid h-10 w-10 place-items-center rounded-xl bg-white/10 text-gold-400 ring-1 ring-white/10">
          <Wheat size={20} />
        </span>
        <div className="min-w-0">
          <p className="truncate text-[15px] font-bold leading-tight text-white">
            {APP_SHORT}
          </p>
          <p className="mt-1 text-[10px] leading-tight tracking-wide text-brand-300">数字产业中台 · 于庄示范</p>
        </div>
      </div>

      {/* 导航 */}
      <nav className="flex-1 space-y-1 px-3" aria-label="运营指挥">
        <p className="px-2 pb-1 text-[10px] font-medium uppercase tracking-wider text-brand-400">
          运营指挥
        </p>
        {NAV_ITEMS.map((item) => {
          const active = pathname === item.href;
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              onClick={onNavigate}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'group flex min-h-11 items-center gap-3 rounded-xl px-3 py-2.5 text-[13px] font-medium transition-all',
                active
                  ? 'bg-brand-700/80 text-white shadow-inner ring-1 ring-white/10'
                  : 'text-brand-200 hover:bg-brand-800/70 hover:text-white',
              )}
            >
              <Icon size={17} className={active ? 'text-gold-400' : 'text-brand-300'} />
              <span className="truncate">{item.label}</span>
              {active && <span className="ml-auto h-1.5 w-1.5 rounded-full bg-gold-400" />}
            </Link>
          );
        })}
      </nav>

      {/* 底部说明 */}
      <div className="border-t border-white/10 px-5 py-4 text-[11px] leading-relaxed text-brand-300">
        <p className="font-medium text-brand-100">于庄数字助农示范平台</p>
        <p className="mt-1 flex items-center gap-1 text-brand-400">演示环境 · 数据仅作项目展示 <ArrowUpRight size={11} /></p>
      </div>
    </aside>
  );
}
