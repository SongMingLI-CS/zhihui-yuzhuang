'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { LayoutDashboard, Loader2 } from 'lucide-react';
import { getStoredUser } from '@/lib/auth';
import { resolveRoleHome } from '@/lib/config';

/** 网关 /b/ 根路径 -> 按角色进入对应分区首页（商家/政府/村委/平台） */
export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    const user = getStoredUser();
    router.replace(resolveRoleHome(user?.role));
  }, [router]);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 text-slate-400">
      <Loader2 size={26} className="animate-spin text-brand-500" />
      <p className="text-sm">正在进入工作台…</p>
      <Link
        href="/dashboard"
        className="inline-flex items-center gap-1.5 rounded-lg border border-brand-200 bg-brand-50 px-3 py-1.5 text-xs font-medium text-brand-700 hover:bg-brand-100"
      >
        <LayoutDashboard size={14} />
        前往产业治理大盘
      </Link>
    </div>
  );
}
