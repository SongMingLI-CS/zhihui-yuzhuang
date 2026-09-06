import Link from 'next/link';
import { Compass, LayoutDashboard } from 'lucide-react';

export default function NotFound() {
  return (
    <div className="grid min-h-[calc(100dvh-8rem)] place-items-center px-4 text-center">
      <div className="max-w-md">
        <span className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-brand-50 text-brand-700"><Compass size={28} /></span>
        <p className="num mt-6 text-sm font-semibold tracking-[0.2em] text-brand-600">404</p>
        <h2 className="mt-2 text-2xl font-bold tracking-tight text-slate-900">没有找到这个页面</h2>
        <p className="mt-3 text-sm leading-6 text-slate-500">链接可能已失效，或该功能尚未开放。返回治理大盘继续操作。</p>
        <Link href="/dashboard" className="mt-6 inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand-700 px-5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-800">
          <LayoutDashboard size={16} />返回治理大盘
        </Link>
      </div>
    </div>
  );
}
