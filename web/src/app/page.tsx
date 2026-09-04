'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { LayoutDashboard, Loader2 } from 'lucide-react';

/** 网关 /b/ 根路径 -> 产业治理大盘 /b/dashboard */
export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/dashboard');
  }, [router]);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 text-slate-400">
      <Loader2 size={26} className="animate-spin text-brand-500" />
      <p className="text-sm">正在进入产业治理大盘…</p>
      <Link
        href="/dashboard"
        className="inline-flex items-center gap-1.5 rounded-lg border border-brand-200 bg-brand-50 px-3 py-1.5 text-xs font-medium text-brand-700 hover:bg-brand-100"
      >
        <LayoutDashboard size={14} />
        立即进入 /dashboard
      </Link>
    </div>
  );
}
