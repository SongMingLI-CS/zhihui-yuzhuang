'use client';

import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PackagePlus, Pencil, Power } from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyBlock, ErrorBlock, LoadingBlock } from '@/components/ui/StateView';
import { useToast } from '@/components/ui/Toast';
import { createProduct, listProducts, toApiError, updateProduct, updateProductStatus } from '@/lib/http';
import type { Product, ProductUpsertRequest } from '@/lib/types';
import { formatCNY, formatInt } from '@/lib/format';

export default function ProductsPage() {
  const { notify } = useToast();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Product | null>(null);
  const [creating, setCreating] = useState(false);

  const { data: products, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['products'],
    queryFn: listProducts,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['products'] });

  const createMutation = useMutation({
    mutationFn: (p: ProductUpsertRequest) => createProduct(p),
    onSuccess: () => { invalidate(); notify('success', '上架成功', '新商品已加入在售目录。'); },
    onError: (e) => notify('error', '上架失败', toApiError(e).message),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: number; payload: ProductUpsertRequest }) => updateProduct(id, payload),
    onSuccess: () => { invalidate(); notify('success', '保存成功', '商品信息已更新。'); },
    onError: (e) => notify('error', '保存失败', toApiError(e).message),
  });

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: string }) => updateProductStatus(id, status),
    onSuccess: (p) => { invalidate(); notify('success', '已切换', `${p.spuName} 已${p.status === 'ON_SALE' ? '上架' : '下架'}。`); },
    onError: (e) => notify('error', '操作失败', toApiError(e).message),
  });

  const list = products ?? [];

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="CATALOG"
        title="商品管理"
        description="特产目录维护：上架新品、编辑价格/库存、上下架切换"
        actions={
          <button
            type="button"
            onClick={() => setCreating(true)}
            className="inline-flex min-h-10 items-center gap-1.5 rounded-xl bg-brand-700 px-3.5 text-sm font-semibold text-white transition hover:bg-brand-800"
          >
            <PackagePlus size={15} />
            上架新商品
          </button>
        }
      />

      <Card bodyClassName="p-0">
        {isLoading ? (
          <LoadingBlock text="加载商品目录…" />
        ) : isError ? (
          <ErrorBlock message={toApiError(error).message} onRetry={() => refetch()} />
        ) : list.length === 0 ? (
          <EmptyBlock>暂无商品，点击右上角「上架新商品」开始维护目录。</EmptyBlock>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-[11px] uppercase tracking-wider text-slate-400">
                  <th className="px-4 py-3 font-semibold sm:px-5">SKU / 商品</th>
                  <th className="px-4 py-3 font-semibold">售价</th>
                  <th className="px-4 py-3 font-semibold">库存</th>
                  <th className="px-4 py-3 font-semibold">状态</th>
                  <th className="px-4 py-3 text-right font-semibold sm:px-5">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {list.map((p) => (
                  <tr key={p.id} className="transition hover:bg-slate-50/60">
                    <td className="px-4 py-3 sm:px-5">
                      <p className="font-semibold text-slate-800">{p.spuName}</p>
                      <p className="mt-0.5 text-xs text-slate-400">
                        {p.skuCode}
                        {p.tenantId !== 'global' ? ` · ${p.tenantId}` : ''}
                      </p>
                    </td>
                    <td className="num px-4 py-3 text-slate-700">{formatCNY(p.price)}</td>
                    <td className="num px-4 py-3 text-slate-700">{formatInt(p.stock)}</td>
                    <td className="px-4 py-3">
                      <Badge tone={p.status === 'ON_SALE' ? 'green' : 'slate'} dot>
                        {p.status === 'ON_SALE' ? '在售' : '已下架'}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-right sm:px-5">
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          onClick={() => setEditing(p)}
                          className="inline-flex min-h-8 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-medium text-slate-600 transition hover:border-brand-300 hover:text-brand-700"
                        >
                          <Pencil size={13} />
                          编辑
                        </button>
                        <button
                          type="button"
                          disabled={statusMutation.isPending}
                          onClick={() => statusMutation.mutate({ id: p.id, status: p.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE' })}
                          className="inline-flex min-h-8 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-medium text-slate-600 transition hover:border-amber-300 hover:text-amber-700 disabled:opacity-50"
                        >
                          <Power size={13} />
                          {p.status === 'ON_SALE' ? '下架' : '上架'}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {(creating || editing) && (
        <ProductFormDialog
          initial={editing}
          busy={createMutation.isPending || updateMutation.isPending}
          onSubmit={(payload) => {
            if (editing) updateMutation.mutate({ id: editing.id, payload });
            else createMutation.mutate(payload);
          }}
          onClose={() => { setCreating(false); setEditing(null); }}
        />
      )}
    </div>
  );
}

function ProductFormDialog({
  initial,
  busy,
  onSubmit,
  onClose,
}: {
  initial: Product | null;
  busy: boolean;
  onSubmit: (payload: ProductUpsertRequest) => void;
  onClose: () => void;
}) {
  const [skuCode, setSkuCode] = useState(initial?.skuCode ?? '');
  const [spuName, setSpuName] = useState(initial?.spuName ?? '');
  const [price, setPrice] = useState(initial ? String(initial.price) : '');
  const [stock, setStock] = useState(initial ? String(initial.stock) : '');

  function submit(e: FormEvent) {
    e.preventDefault();
    onSubmit({
      skuCode: skuCode.trim(),
      spuName: spuName.trim(),
      price: Number(price),
      stock: Number(stock),
      status: initial?.status ?? 'ON_SALE',
    });
  }

  const inputCls =
    'h-11 w-full rounded-xl border border-slate-200 px-3 text-sm text-slate-800 outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-100';

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 px-4 backdrop-blur-[2px]"
      role="dialog"
      aria-modal="true"
      aria-label={initial ? '编辑商品' : '上架新商品'}
    >
      <form onSubmit={submit} className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl">
        <h2 className="text-base font-bold text-slate-900">{initial ? '编辑商品' : '上架新商品'}</h2>
        <div className="mt-4 space-y-3">
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">SKU 编码</span>
            <input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} required maxLength={64} placeholder="如 SKU1004" className={inputCls} />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">商品名称</span>
            <input value={spuName} onChange={(e) => setSpuName(e.target.value)} required maxLength={255} placeholder="如 于庄石磨黑芝麻香油" className={inputCls} />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">售价（元）</span>
            <input type="number" value={price} onChange={(e) => setPrice(e.target.value)} required min="0.01" step="0.01" placeholder="如 58.00" className={inputCls} />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-xs font-medium text-slate-600">库存（件）</span>
            <input type="number" value={stock} onChange={(e) => setStock(e.target.value)} required min="0" step="1" placeholder="如 300" className={inputCls} />
          </label>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="min-h-10 rounded-xl border border-slate-200 px-4 text-sm font-medium text-slate-600 transition hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={busy}
            className="min-h-10 rounded-xl bg-brand-700 px-4 text-sm font-semibold text-white transition hover:bg-brand-800 disabled:opacity-60"
          >
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </form>
    </div>
  );
}

