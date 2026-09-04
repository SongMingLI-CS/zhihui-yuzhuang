'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Wheat } from 'lucide-react';
import { cn } from '@/lib/cn';
import { APP_SHORT } from '@/lib/config';
import { NAV_ITEMS } from './navItems';

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="flex w-[228px] shrink-0 flex-col bg-brand-900 text-brand-100">
      {/* 品牌区 */}
      <div className="flex items-center gap-2.5 px-5 py-5">
        <span className="grid h-9 w-9 place-items-center rounded-xl bg-brand-700 text-gold-400 ring-1 ring-white/10">
          <Wheat size={20} />
        </span>
        <div className="min-w-0">
          <p className="truncate text-[15px] font-bold leading-tight text-white">
            {APP_SHORT}
          </p>
          <p className="text-[10px] leading-tight text-brand-300">数字产业中台与治理大脑</p>
        </div>
      </div>

      {/* 导航 */}
      <nav className="mt-2 flex-1 space-y-1 px-3">
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
              className={cn(
                'group flex items-center gap-3 rounded-xl px-3 py-2.5 text-[13px] font-medium transition-colors',
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
      <div className="border-t border-white/10 px-5 py-4 text-[10px] leading-relaxed text-brand-400">
        <p>网关入口 /b/ · web:3000</p>
        <p>basePath: /b · standalone 产物</p>
      </div>
    </aside>
  );
}
