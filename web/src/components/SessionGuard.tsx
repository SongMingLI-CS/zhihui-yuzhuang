'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Loader2 } from 'lucide-react';
import { fetchMe } from '@/lib/http';
import { getStoredUser, persistUser, setCurrentUser } from '@/lib/auth';

/**
 * 路由级会话守卫（阶段 A P0-5）。
 *
 * <p>在 `/b/*` 全量生效：未登录/会话过期时直接进入登录页（由 next/navigation 按 basePath
 * 解析为 `/b/login`），不再等待业务 API 401；页面刷新后凭 HttpOnly 会话 Cookie
 * 调用 `/auth/me` 恢复登录态。
 *
 * <p>注意：本守卫只用于体验与跳转，<b>真正的授权由后端强制</b>（前端隐藏不构成授权）。
 */
const PUBLIC_PATHS = ['/login', '/change-password'];

export function SessionGuard({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (PUBLIC_PATHS.includes(pathname)) {
      setReady(true);
      return;
    }
    let cancelled = false;
    (async () => {
      const stored = getStoredUser();
      if (stored) {
        setCurrentUser(stored);
        setReady(true);
        return;
      }
      try {
        const me = await fetchMe();
        if (cancelled) return;
        persistUser(me);
        setCurrentUser(me);
        setReady(true);
      } catch {
        if (!cancelled) router.replace('/login');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [pathname, router]);

  if (!ready) {
    return (
      <div className="flex h-dvh items-center justify-center gap-3 text-slate-400">
        <Loader2 size={22} className="animate-spin text-brand-500" />
        <span className="text-sm">正在校验登录态…</span>
      </div>
    );
  }
  return <>{children}</>;
}
