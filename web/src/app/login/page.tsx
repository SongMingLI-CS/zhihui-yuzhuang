'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Loader2, LogIn, ShieldCheck, Wheat } from 'lucide-react';
import { login, setAccessToken, toApiError } from '@/lib/http';
import { persistUser, setCurrentUser } from '@/lib/auth';
import { resolveTenant, setTenant } from '@/lib/tenant';
import { APP_SHORT, resolveRoleHome } from '@/lib/config';

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    setBusy(true);
    try {
      const res = await login({ username: username.trim(), password });
      // 令牌仅在内存保存；会话主体依托服务端下发的 HttpOnly Cookie
      setAccessToken(res.token);
      persistUser(res.user);
      setCurrentUser(res.user);
      // 受保护端点租户取 JWT tenantId：登录后租户选择器应锁定为账号所属租户
      setTenant(resolveTenant(res.user.tenantId));
      if (res.mustChangePassword) {
        router.replace('/change-password');
        return;
      }
      // 角色分区落地：商家 / 政府 / 村委 / 平台管理员各入其首页
      router.replace(resolveRoleHome(res.user.role));
    } catch (err) {
      setError(toApiError(err).message);
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-[var(--canvas)] px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center gap-3 text-center">
          <span className="grid h-14 w-14 place-items-center rounded-2xl bg-[#163b2a] text-gold-400 shadow-lg">
            <Wheat size={26} />
          </span>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-slate-900">{APP_SHORT}</h1>
            <p className="mt-1 text-xs text-slate-400">数字产业中台与治理大脑 · 运营登录</p>
          </div>
        </div>

        <form
          onSubmit={onSubmit}
          className="rounded-2xl border border-slate-200 bg-white p-6 shadow-[var(--shadow-float)]"
        >
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">用户名</span>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={2}
              maxLength={64}
              autoComplete="username"
              placeholder="请输入用户名"
              className="h-11 w-full rounded-xl border border-slate-200 px-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </label>
          <label className="mt-4 block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">密码</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={6}
              maxLength={128}
              autoComplete="current-password"
              placeholder="请输入密码"
              className="h-11 w-full rounded-xl border border-slate-200 px-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </label>

          {error && (
            <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={busy}
            className="mt-5 flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-brand-700 text-sm font-semibold text-white transition hover:bg-brand-800 disabled:opacity-60"
          >
            {busy ? <Loader2 size={16} className="animate-spin" /> : <LogIn size={16} />}
            {busy ? '登录中…' : '登录'}
          </button>
        </form>

        <div className="mt-4 flex items-start gap-2 rounded-xl border border-dashed border-slate-200 bg-white/60 px-3 py-2.5 text-[11px] leading-relaxed text-slate-400">
          <ShieldCheck size={14} className="mt-0.5 shrink-0 text-brand-500" />
          <p>
            账号由平台管理员发放，首次登录需修改初始密码。政府端、商家端与村委端按角色分区进入；
            请勿共享账号。
          </p>
        </div>
      </div>
    </main>
  );
}
