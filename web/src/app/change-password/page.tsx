'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { KeyRound, Loader2, ShieldAlert } from 'lucide-react';
import { changePassword, toApiError } from '@/lib/http';
import { getStoredUser } from '@/lib/auth';
import { resolveRoleHome } from '@/lib/config';

/**
 * 首次登录强制改密页（阶段 A）。
 *
 * <p>演示账号/管理员重置后的账号以 {@code mustChangePassword=true} 登录，必须先改密才能进入
 * 业务分区。新密码需满足服务端 {@code PasswordPolicy}（≥8 位且含字母与数字）。
 */
export default function ChangePasswordPage() {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    if (newPassword !== confirmPassword) {
      setError('两次输入的新密码不一致');
      return;
    }
    setBusy(true);
    try {
      await changePassword(currentPassword, newPassword);
      const user = getStoredUser();
      router.replace(resolveRoleHome(user?.role));
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
            <KeyRound size={26} />
          </span>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-slate-900">首次登录 · 修改密码</h1>
            <p className="mt-1 text-xs text-slate-400">为保障账号安全，请设置新的登录密码</p>
          </div>
        </div>

        <form
          onSubmit={onSubmit}
          className="rounded-2xl border border-slate-200 bg-white p-6 shadow-[var(--shadow-float)]"
        >
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">当前密码</span>
            <input
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              required
              minLength={6}
              maxLength={128}
              autoComplete="current-password"
              className="h-11 w-full rounded-xl border border-slate-200 px-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </label>
          <label className="mt-4 block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">新密码（≥8 位，含字母与数字）</span>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              required
              minLength={8}
              maxLength={128}
              autoComplete="new-password"
              className="h-11 w-full rounded-xl border border-slate-200 px-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100"
            />
          </label>
          <label className="mt-4 block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">确认新密码</span>
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              required
              minLength={8}
              maxLength={128}
              autoComplete="new-password"
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
            {busy ? <Loader2 size={16} className="animate-spin" /> : <ShieldAlert size={16} />}
            {busy ? '提交中…' : '确认修改'}
          </button>
        </form>
      </div>
    </main>
  );
}
