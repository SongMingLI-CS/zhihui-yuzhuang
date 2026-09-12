'use client';

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Loader2, PackageSearch, RefreshCw } from 'lucide-react';
import { listMerchantProducts, toApiError, updateProductStatus } from '@/lib/http';
import type { MerchantProductParams, Product } from '@/lib/types';

/**
 * 商家工作台 · 商品管理（阶段 C）。
 *
 * <p>真实调用 {@code GET /api/v1/merchant/products}：包含草稿/在售/下架/归档，
 * 修复历史“下架即从列表消失、无法再上架”的问题；上下架走
 * {@code PATCH /api/v1/products/{id}/status}，数据域与权限由服务端强制。
 */

const STATUS_META: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: '草稿', cls: 'bg-slate-100 text-slate-600' },
  ON_SALE: { label: '在售', cls: 'bg-emerald-50 text-emerald-700' },
  OFF_SHELF: { label: '已下架', cls: 'bg-amber-50 text-amber-700' },
  ARCHIVED: { label: '已归档', cls: 'bg-slate-100 text-slate-500' },
};

const FILTERS: Array<{ key: string; label: string }> = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'ON_SALE', label: '在售' },
  { key: 'OFF_SHELF', label: '已下架' },
  { key: 'ARCHIVED', label: '已归档' },
];

export default function MerchantProductsPage() {
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const params: MerchantProductParams = {
    status: status || undefined,
    keyword: keyword.trim() || undefined,
    page,
    pageSize: 10,
  };

  const query = useQuery({
    queryKey: ['merchant-products', params],
    queryFn: () => listMerchantProducts(params),
  });

  const toggle = useMutation({
    mutationFn: (p: Product) =>
      updateProductStatus(p.id, p.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['merchant-products'] });
    },
    onError: (err) => setError(toApiError(err).message),
  });

  const data = query.data;

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-slate-900">
            <PackageSearch size={20} className="text-brand-600" />
            我的商品（含草稿与下架）
          </h2>
          <p className="mt-1 text-xs text-slate-500">
            仅展示本商家租户商品；上下架、编辑由服务端按租户与角色强制校验。
          </p>
        </div>
        <button
          type="button"
          onClick={() => void query.refetch()}
          className="flex h-9 items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 text-xs font-medium text-slate-600 hover:border-brand-300 hover:text-brand-700"
        >
          <RefreshCw size={14} /> 刷新
        </button>
      </header>

      <div className="flex flex-wrap items-center gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.key || 'all'}
            type="button"
            onClick={() => { setStatus(f.key); setPage(1); }}
            className={`h-8 rounded-lg px-3 text-xs font-medium transition ${
              status === f.key
                ? 'bg-brand-700 text-white'
                : 'border border-slate-200 bg-white text-slate-600 hover:border-brand-300'
            }`}
          >
            {f.label}
          </button>
        ))}
        <input
          value={keyword}
          onChange={(e) => { setKeyword(e.target.value); setPage(1); }}
          placeholder="搜索商品名 / SKU"
          className="h-8 w-48 rounded-lg border border-slate-200 px-3 text-xs outline-none focus:border-brand-400"
        />
      </div>

      {error && <p className="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600">{error}</p>}

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
        {query.isLoading ? (
          <div className="flex items-center justify-center gap-2 py-14 text-slate-400">
            <Loader2 size={18} className="animate-spin" />
            <span className="text-sm">加载商品中…</span>
          </div>
        ) : query.isError ? (
          <div className="py-14 text-center text-sm text-red-500">{toApiError(query.error).message}</div>
        ) : !data || data.items.length === 0 ? (
          <div className="py-14 text-center text-sm text-slate-400">当前筛选条件下暂无商品</div>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-400">
              <tr>
                <th className="px-4 py-2.5">SKU</th>
                <th className="px-4 py-2.5">商品名称</th>
                <th className="px-4 py-2.5">单价</th>
                <th className="px-4 py-2.5">库存</th>
                <th className="px-4 py-2.5">状态</th>
                <th className="px-4 py-2.5 text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((p) => {
                const meta = STATUS_META[p.status] ?? { label: p.status, cls: 'bg-slate-100 text-slate-600' };
                return (
                  <tr key={p.id} className="border-t border-slate-100">
                    <td className="px-4 py-2.5 font-mono text-xs text-slate-500">{p.skuCode}</td>
                    <td className="px-4 py-2.5 text-slate-800">{p.spuName}</td>
                    <td className="px-4 py-2.5 tabular-nums text-slate-700">¥{Number(p.price).toFixed(2)}</td>
                    <td className="px-4 py-2.5 tabular-nums text-slate-700">{p.stock}</td>
                    <td className="px-4 py-2.5">
                      <span className={`rounded-md px-2 py-0.5 text-[11px] font-medium ${meta.cls}`}>{meta.label}</span>
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <button
                        type="button"
                        disabled={toggle.isPending || p.status === 'ARCHIVED' || p.status === 'DRAFT'}
                        onClick={() => toggle.mutate(p)}
                        className="h-7 rounded-lg border border-slate-200 px-2.5 text-xs font-medium text-slate-600 transition hover:border-brand-300 hover:text-brand-700 disabled:opacity-40"
                      >
                        {p.status === 'ON_SALE' ? '下架' : '上架'}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 text-xs text-slate-500">
          <span>
            第 {data.page} / {data.totalPages} 页 · 共 {data.total} 条
          </span>
          <button
            type="button"
            disabled={page <= 1}
            onClick={() => setPage((v) => Math.max(1, v - 1))}
            className="h-7 rounded-lg border border-slate-200 px-2 disabled:opacity-40"
          >
            上一页
          </button>
          <button
            type="button"
            disabled={page >= data.totalPages}
            onClick={() => setPage((v) => v + 1)}
            className="h-7 rounded-lg border border-slate-200 px-2 disabled:opacity-40"
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}
