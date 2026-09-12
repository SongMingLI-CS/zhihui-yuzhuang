'use client';

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Loader2, ServerCog, UserCog } from 'lucide-react';
import {
  listAdminTenants,
  listAdminUsers,
  resetAdminUserPassword,
  toApiError,
  updateAdminUserStatus,
} from '@/lib/http';
import type { AdminUser } from '@/lib/types';

/**
 * 平台管理最小 UI（阶段 B）。
 *
 * <p>仅 {@code PLATFORM_ADMIN} 可用（服务端 {@code /api/v1/admin/**} 端点策略强制）：
 * 查看租户、查看账号、停用/启用、重置密码（一次性临时口令 + 强制首次改密）。
 */
const ROLE_LABEL: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员',
  GOVERNMENT: '政府（只读）',
  VILLAGE: '村委',
  COOPERATIVE: '合作社/商家',
  FARMER: '农户',
  CONSUMER: '消费者',
};

export default function PlatformAdminPage() {
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const tenants = useQuery({ queryKey: ['admin-tenants'], queryFn: listAdminTenants });
  const users = useQuery({ queryKey: ['admin-users'], queryFn: () => listAdminUsers() });

  const statusMutation = useMutation({
    mutationFn: (u: AdminUser) =>
      updateAdminUserStatus(u.id, u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'),
    onSuccess: (u) => {
      setError(null);
      setNote(`账号 ${u.username} 状态已更新为 ${u.status}`);
      void queryClient.invalidateQueries({ queryKey: ['admin-users'] });
    },
    onError: (err) => setError(toApiError(err).message),
  });

  const resetMutation = useMutation({
    mutationFn: (u: AdminUser) => resetAdminUserPassword(u.id),
    onSuccess: (r) => {
      setError(null);
      setNote(`账号 ${r.username} 临时口令：${r.temporaryPassword}（首次登录需改密，请安全转交）`);
    },
    onError: (err) => setError(toApiError(err).message),
  });

  return (
    <div className="space-y-5">
      <header>
        <h2 className="flex items-center gap-2 text-lg font-bold text-slate-900">
          <ServerCog size={20} className="text-brand-600" />
          平台管理 · 租户与账号
        </h2>
        <p className="mt-1 text-xs text-slate-500">
          账号由平台管理员统一发放；政府账号需显式授予区域/租户范围后方可查看治理大屏。
        </p>
      </header>

      {error && <p className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">{error}</p>}
      {note && <p className="rounded-lg bg-emerald-50 px-3 py-2 text-xs text-emerald-700">{note}</p>}

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
        <div className="border-b border-slate-100 px-4 py-2.5 text-xs font-semibold text-slate-600">
          租户（{tenants.data?.length ?? 0}）
        </div>
        {tenants.isLoading ? (
          <Loading />
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-400">
              <tr>
                <th className="px-4 py-2.5">标识</th>
                <th className="px-4 py-2.5">名称</th>
                <th className="px-4 py-2.5">类型</th>
                <th className="px-4 py-2.5">属地</th>
                <th className="px-4 py-2.5">状态</th>
              </tr>
            </thead>
            <tbody>
              {(tenants.data ?? []).map((t) => (
                <tr key={t.id} className="border-t border-slate-100">
                  <td className="px-4 py-2.5 font-mono text-xs text-slate-500">{t.tenantId}</td>
                  <td className="px-4 py-2.5 text-slate-800">{t.name}</td>
                  <td className="px-4 py-2.5 text-xs text-slate-500">{t.tenantType ?? '—'}</td>
                  <td className="px-4 py-2.5 text-xs text-slate-500">{t.region ?? '—'}</td>
                  <td className="px-4 py-2.5 text-xs text-slate-500">{t.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
        <div className="flex items-center gap-1.5 border-b border-slate-100 px-4 py-2.5 text-xs font-semibold text-slate-600">
          <UserCog size={13} /> 账号（{users.data?.length ?? 0}）
        </div>
        {users.isLoading ? (
          <Loading />
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-400">
              <tr>
                <th className="px-4 py-2.5">用户名</th>
                <th className="px-4 py-2.5">姓名</th>
                <th className="px-4 py-2.5">角色</th>
                <th className="px-4 py-2.5">租户</th>
                <th className="px-4 py-2.5">状态</th>
                <th className="px-4 py-2.5 text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {(users.data ?? []).map((u) => (
                <tr key={u.id} className="border-t border-slate-100">
                  <td className="px-4 py-2.5 font-mono text-xs text-slate-500">{u.username}</td>
                  <td className="px-4 py-2.5 text-slate-800">{u.displayName}</td>
                  <td className="px-4 py-2.5 text-xs text-slate-500">{ROLE_LABEL[u.role] ?? u.role}</td>
                  <td className="px-4 py-2.5 font-mono text-[11px] text-slate-400">{u.tenantId}</td>
                  <td className="px-4 py-2.5 text-xs">
                    <span className={u.status === 'ACTIVE' ? 'text-emerald-600' : 'text-red-500'}>
                      {u.status === 'ACTIVE' ? '启用' : '停用'}
                    </span>
                    {u.mustChangePassword ? <span className="ml-1 text-amber-500">· 待改密</span> : null}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <button
                      type="button"
                      disabled={statusMutation.isPending}
                      onClick={() => statusMutation.mutate(u)}
                      className="h-7 rounded-lg border border-slate-200 px-2 text-xs text-slate-600 hover:border-brand-300 hover:text-brand-700 disabled:opacity-40"
                    >
                      {u.status === 'ACTIVE' ? '停用' : '启用'}
                    </button>
                    <button
                      type="button"
                      disabled={resetMutation.isPending}
                      onClick={() => resetMutation.mutate(u)}
                      className="ml-1.5 inline-flex h-7 items-center gap-1 rounded-lg border border-slate-200 px-2 text-xs text-slate-600 hover:border-amber-300 hover:text-amber-700 disabled:opacity-40"
                    >
                      <KeyRound size={11} /> 重置密码
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function Loading() {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-slate-400">
      <Loader2 size={16} className="animate-spin" />
      <span className="text-xs">加载中…</span>
    </div>
  );
}
