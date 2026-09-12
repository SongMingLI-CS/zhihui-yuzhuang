'use client';

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Loader2 } from 'lucide-react';
import { listMarketingTasks, marketingTaskAction, toApiError } from '@/lib/http';
import type { MarketingReviewStatus, MarketingTaskItem } from '@/lib/types';

/**
 * 营销任务台账与审批（阶段 F）。
 *
 * <p>数据来自 {@code GET /ai/v1/marketing/tasks}（持久化的生成任务与审批留痕）；
 * approve / reject / publish 三个动作均由服务端状态机强制：**未经人工批准不得标记为已发布**。
 */
export const STATUS_META: Record<string, { label: string; cls: string }> = {
  PENDING_HUMAN_REVIEW: { label: '待人工复核', cls: 'bg-amber-50 text-amber-700' },
  APPROVED: { label: '已通过', cls: 'bg-emerald-50 text-emerald-700' },
  REJECTED: { label: '已驳回', cls: 'bg-red-50 text-red-600' },
};

const FILTERS: Array<{ key: '' | MarketingReviewStatus; label: string }> = [
  { key: '', label: '全部' },
  { key: 'PENDING_HUMAN_REVIEW', label: '待复核' },
  { key: 'APPROVED', label: '已通过' },
  { key: 'REJECTED', label: '已驳回' },
];

export function MarketingTaskLedger() {
  const [status, setStatus] = useState<'' | MarketingReviewStatus>('');
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ['marketing-tasks', status],
    queryFn: () => listMarketingTasks({ status: status || undefined, pageSize: 20 }),
  });

  const act = useMutation({
    mutationFn: (vars: { task: MarketingTaskItem; action: 'approve' | 'reject' | 'publish' }) =>
      marketingTaskAction(vars.task.id, vars.action),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['marketing-tasks'] });
    },
    onError: (err) => setError(toApiError(err).message),
  });

  const data = query.data;

  return (
    <section className="rounded-2xl border border-slate-200 bg-white">
      <header className="flex flex-wrap items-center gap-2 border-b border-slate-100 px-4 py-3">
        <ClipboardList size={16} className="text-brand-600" />
        <h3 className="text-sm font-semibold text-slate-800">营销任务台账与审批</h3>
        <span className="text-[11px] text-slate-400">
          生成任务与审批留痕均落库；审批通过后才能标记发布
        </span>
        <div className="ml-auto flex items-center gap-1.5">
          {FILTERS.map((f) => (
            <button
              key={f.key || 'all'}
              type="button"
              onClick={() => setStatus(f.key)}
              className={`h-7 rounded-lg px-2.5 text-[11px] font-medium transition ${
                status === f.key
                  ? 'bg-brand-700 text-white'
                  : 'border border-slate-200 bg-white text-slate-600 hover:border-brand-300'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </header>

      {error && <p className="mx-4 mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">{error}</p>}

      {query.isLoading ? (
        <div className="flex items-center justify-center gap-2 py-10 text-slate-400">
          <Loader2 size={16} className="animate-spin" />
          <span className="text-xs">加载任务台账…</span>
        </div>
      ) : query.isError ? (
        <div className="px-4 py-10 text-center text-xs text-red-500">
          {toApiError(query.error).message}
        </div>
      ) : !data || data.items.length === 0 ? (
        <div className="px-4 py-10 text-center text-xs text-slate-400">
          暂无任务记录（生成一次营销文案后此处会出现待审批任务）
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wide text-slate-400">
              <tr>
                <th className="px-4 py-2">任务</th>
                <th className="px-4 py-2">商品</th>
                <th className="px-4 py-2">合规</th>
                <th className="px-4 py-2">状态</th>
                <th className="px-4 py-2">创建/审批</th>
                <th className="px-4 py-2 text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((t) => (
                <TaskRow
                  key={t.id}
                  task={t}
                  busy={act.isPending}
                  onAct={(action) => act.mutate({ task: t, action })}
                />
              ))}
            </tbody>
          </table>
          <p className="px-4 py-2 text-[10px] text-slate-400">
            共 {data.total} 条 · 第 {data.page}/{data.totalPages || 1} 页
          </p>
        </div>
      )}
    </section>
  );
}

function TaskRow({
  task,
  busy,
  onAct,
}: {
  task: MarketingTaskItem;
  busy: boolean;
  onAct: (action: 'approve' | 'reject' | 'publish') => void;
}) {
  const meta = STATUS_META[task.reviewStatus] ?? {
    label: task.reviewStatus,
    cls: 'bg-slate-100 text-slate-600',
  };
  const pending = task.reviewStatus === 'PENDING_HUMAN_REVIEW';
  const approved = task.reviewStatus === 'APPROVED';
  return (
    <tr className="border-t border-slate-100">
      <td className="px-4 py-2 font-mono text-slate-500">#{task.id}</td>
      <td className="px-4 py-2 text-slate-800">{task.productName}</td>
      <td className="px-4 py-2 tabular-nums text-slate-600">
        {task.complianceScore}
        {task.compliancePassed ? ' · 通过' : ' · 需整改'}
      </td>
      <td className="px-4 py-2">
        <span className={`rounded-md px-2 py-0.5 text-[10px] font-medium ${meta.cls}`}>{meta.label}</span>
        {task.publishedAt ? <span className="ml-1 text-[10px] text-emerald-600">已发布</span> : null}
      </td>
      <td className="px-4 py-2 text-[10px] text-slate-400">
        <div>
          {task.createdBy || '-'} · {task.createdAt ? task.createdAt.slice(0, 19) : '-'}
        </div>
        {task.reviewedBy ? (
          <div>
            审批：{task.reviewedBy} · {task.reviewedAt ? task.reviewedAt.slice(0, 19) : ''}
          </div>
        ) : null}
      </td>
      <td className="px-4 py-2 text-right">
        <button
          type="button"
          disabled={!pending || busy}
          onClick={() => onAct('approve')}
          className="inline-flex h-6 items-center gap-1 rounded-lg border border-slate-200 px-2 text-[10px] text-slate-600 hover:border-emerald-300 hover:text-emerald-700 disabled:opacity-40"
        >
          通过
        </button>
        <button
          type="button"
          disabled={!pending || busy}
          onClick={() => onAct('reject')}
          className="ml-1 inline-flex h-6 items-center gap-1 rounded-lg border border-slate-200 px-2 text-[10px] text-slate-600 hover:border-red-300 hover:text-red-600 disabled:opacity-40"
        >
          驳回
        </button>
        <button
          type="button"
          disabled={!approved || !!task.publishedAt || busy}
          onClick={() => onAct('publish')}
          className="ml-1 inline-flex h-6 items-center gap-1 rounded-lg border border-slate-200 px-2 text-[10px] text-slate-600 hover:border-brand-300 hover:text-brand-700 disabled:opacity-40"
        >
          标记发布
        </button>
      </td>
    </tr>
  );
}
