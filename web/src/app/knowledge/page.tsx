'use client';

import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import {
  BookOpenCheck,
  Bot,
  FileText,
  Info,
  Layers,
  Library,
  Loader2,
  MessageSquareText,
  RefreshCw,
  Search,
  UploadCloud,
} from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyBlock } from '@/components/ui/StateView';
import { useToast } from '@/components/ui/Toast';
import { QaDrawer } from '@/components/knowledge/QaDrawer';
import { AGRI_QUICK_QUESTIONS, type DocStatus, type KnowledgeDoc } from '@/lib/demo';
import { fetchKnowledgeDocs, toApiError, uploadKnowledgeDoc } from '@/lib/http';
import type { KnowledgeDocMeta } from '@/lib/types';
import { formatInt } from '@/lib/format';

const STATUS_META: Record<DocStatus, { label: string; tone: 'green' | 'amber' | 'red' }> = {
  READY: { label: '已就绪', tone: 'green' },
  PROCESSING: { label: '切片中', tone: 'amber' },
  FAILED: { label: '失败', tone: 'red' },
};

/** 租户显示名：global → 全局知识库；其余回退租户 ID。 */
function tenantLabel(tenantId: string): string {
  return tenantId === 'global' ? '全局知识库' : tenantId;
}

/** 展示时间：ISO 掐头为「YYYY-MM-DD HH:mm」。 */
function fmtCreated(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : '';
}

/** 将 ai-service 元信息映射为表格视图行（status 固定 READY，同步入库成功即就绪）。 */
function toViewDoc(meta: KnowledgeDocMeta): KnowledgeDoc {
  return {
    id: meta.id,
    title: meta.title,
    tenantId: meta.tenantId,
    tenantName: tenantLabel(meta.tenantId),
    source: meta.source,
    chunks: meta.chunks,
    status: 'READY',
    createdAt: fmtCreated(meta.createdAt),
  };
}

export default function KnowledgePage() {
  const { notify } = useToast();
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [query, setQuery] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [initialQuestion, setInitialQuestion] = useState<string | null>(null);

  // 真实知识库元数据（ai-service /knowledge/docs），null=首次加载中
  const [realDocs, setRealDocs] = useState<KnowledgeDocMeta[] | null>(null);
  const [listState, setListState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [uploading, setUploading] = useState(false);

  async function refreshDocs() {
    try {
      const list = await fetchKnowledgeDocs();
      setRealDocs(list);
      setListState('ready');
    } catch (err) {
      setListState('error');
      setRealDocs([]);
      notify('error', '知识库列表加载失败', toApiError(err).message);
    }
  }

  useEffect(() => {
    void refreshDocs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setUploading(true);
    try {
      const result = await uploadKnowledgeDoc(file);
      notify('success', '入库成功', `《${result.title}》已切片 ${result.chunks} 片（${result.embeddingMode} 向量）`);
      await refreshDocs();
    } catch (err) {
      notify('error', '入库失败', toApiError(err).message);
    } finally {
      setUploading(false);
    }
  }

  const totalChunks = useMemo(
    () => (realDocs ?? []).reduce((s, d) => s + d.chunks, 0),
    [realDocs],
  );

  const docs = useMemo(() => {
    const q = query.trim().toLowerCase();
    const source = (realDocs ?? []).map(toViewDoc);
    if (!q) return source;
    return source.filter(
      (d) =>
        d.title.toLowerCase().includes(q) ||
        d.tenantName.toLowerCase().includes(q) ||
        d.source.toLowerCase().includes(q),
    );
  }, [realDocs, query]);

  const openVerify = (item: KnowledgeDoc) => {
    setInitialQuestion(AGRI_QUICK_QUESTIONS[Math.max(0, (item.id - 1) % AGRI_QUICK_QUESTIONS.length)]);
    setDrawerOpen(true);
  };

  const openEmpty = () => {
    setInitialQuestion(null);
    setDrawerOpen(true);
  };

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="KNOWLEDGE & RAG"
        title="农技知识库"
        description="统一管理农技与惠农政策资料，通过可溯源检索验证每条 AI 回答。"
        actions={
          <Badge tone={listState === 'error' ? 'red' : listState === 'ready' ? 'green' : 'amber'}>
            <Info size={12} />
            {listState === 'ready' ? '实时数据' : listState === 'error' ? '加载失败' : '加载中'}
          </Badge>
        }
      />

      {/* 概览条 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Card padded className="sm:col-span-1">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-brand-50 text-brand-600">
              <Library size={18} />
            </span>
            <div>
              <p className="num text-xl font-bold text-slate-800">{realDocs?.length ?? 0} 篇</p>
              <p className="text-xs text-slate-400">已接入知识文献</p>
            </div>
          </div>
        </Card>
        <Card padded className="sm:col-span-1">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-sky-50 text-sky-600">
              <Layers size={18} />
            </span>
            <div>
              <p className="num text-xl font-bold text-slate-800">{formatInt(totalChunks)}</p>
              <p className="text-xs text-slate-400">检索切片（Chunk）总数</p>
            </div>
          </div>
        </Card>
        <Card padded className="sm:col-span-1">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-violet-50 text-violet-600">
              <MessageSquareText size={18} />
            </span>
            <div>
              <p className="text-xl font-bold text-slate-800">RAG 沙盒</p>
              <p className="text-xs text-slate-400">点击右侧按钮发起真实检索</p>
            </div>
          </div>
        </Card>
      </div>

      {/* 文献表格 */}
      <Card
        title="已切片文献"
        icon={<BookOpenCheck size={16} />}
        subtitle="RAG 向量库入库明细（切片语义化拆分 · 租户隔离可见）"
        actions={
          <div className="flex items-center gap-2">
            <div className="relative min-w-0 flex-1 sm:flex-none">
              <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-300" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="搜索标题 / 租户 / 来源"
                aria-label="搜索知识文献"
                className="control h-9 w-full pl-8 pr-2 text-xs text-slate-700 placeholder:text-slate-400 sm:w-56"
              />
            </div>
            <button
              type="button"
              onClick={openEmpty}
              className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-xl border border-brand-200 bg-brand-50 px-3 text-xs font-semibold text-brand-700 transition hover:bg-brand-700 hover:text-white"
            >
              <Bot size={14} />
              农技检索验证
            </button>
            <button
              type="button"
              onClick={() => void refreshDocs()}
              disabled={listState === 'loading'}
              className="inline-flex h-9 shrink-0 items-center justify-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 text-slate-500 shadow-sm transition hover:border-brand-300 hover:text-brand-700 disabled:opacity-60"
              aria-label="刷新知识库列表"
              title="刷新列表"
            >
              <RefreshCw size={14} className={listState === 'loading' ? 'animate-spin' : ''} />
            </button>
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              disabled={uploading}
              className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-xl bg-brand-700 px-3 text-xs font-semibold text-white shadow-sm transition hover:bg-brand-800 disabled:opacity-60"
            >
              {uploading ? <Loader2 size={14} className="animate-spin" /> : <UploadCloud size={14} />}
              {uploading ? '切片入库中' : '上传知识文档'}
            </button>
            <input
              ref={fileRef}
              type="file"
              accept=".txt,.md,.markdown"
              className="hidden"
              onChange={handleFileChange}
            />
          </div>
        }
        bodyClassName="p-0"
      >
        <div className="scrollbar-thin hidden overflow-x-auto md:block">
          <table className="w-full min-w-[860px] text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[11px] uppercase tracking-wide text-slate-400">
                <th className="px-5 py-3 font-medium">文献标题</th>
                <th className="px-4 py-3 font-medium">所属租户</th>
                <th className="px-4 py-3 text-center font-medium">切片数</th>
                <th className="px-4 py-3 text-center font-medium">状态</th>
                <th className="px-4 py-3 font-medium">入库时间</th>
                <th className="px-5 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => {
                const st = STATUS_META[d.status];
                return (
                  <tr
                    key={d.id}
                    className="group border-b border-slate-50 transition-colors last:border-0 hover:bg-brand-50/30"
                  >
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-2.5">
                        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500 group-hover:bg-brand-100 group-hover:text-brand-700">
                          <FileText size={15} />
                        </span>
                        <div className="min-w-0">
                          <p className="max-w-[320px] truncate text-[13px] font-medium text-slate-700">
                            {d.title}
                          </p>
                          <p className="num max-w-[280px] truncate text-[10px] text-slate-300">
                            {d.source}
                          </p>
                        </div>
                      </div>
                    </td>
                    <td className="px-4 py-3.5">
                      <span className="text-xs text-slate-500">{d.tenantName}</span>
                    </td>
                    <td className="px-4 py-3.5 text-center">
                      <span className="num text-[13px] font-semibold text-slate-700">{d.chunks}</span>
                    </td>
                    <td className="px-4 py-3.5 text-center">
                      <Badge tone={st.tone} dot>
                        {st.label}
                      </Badge>
                    </td>
                    <td className="px-4 py-3.5">
                      <span className="num text-xs text-slate-400">{d.createdAt}</span>
                    </td>
                    <td className="px-5 py-3.5 text-right">
                      <button
                        type="button"
                        onClick={() => openVerify(d)}
                        className="inline-flex items-center gap-1 rounded-lg border border-brand-200 px-2.5 py-1 text-xs font-medium text-brand-700 transition hover:bg-brand-700 hover:text-white"
                      >
                        <Bot size={12} />
                        检索验证
                      </button>
                    </td>
                  </tr>
                );
              })}
              {docs.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-5 py-10 text-center text-xs text-slate-400">
                    未找到匹配的文献，试试其他关键词
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="divide-y divide-slate-100 md:hidden">
          {docs.map((d) => {
            const st = STATUS_META[d.status];
            return (
              <article key={d.id} className="p-4">
                <div className="flex items-start gap-3">
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-slate-100 text-slate-600"><FileText size={17} /></span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-2">
                      <h4 className="text-sm font-semibold leading-5 text-slate-800">{d.title}</h4>
                      <Badge tone={st.tone} dot>{st.label}</Badge>
                    </div>
                    <p className="mt-1 text-xs leading-5 text-slate-500">{d.tenantName}</p>
                    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-400">
                      <span>{d.chunks} 个切片</span><span>{d.createdAt}</span>
                    </div>
                    <button type="button" onClick={() => openVerify(d)} className="mt-3 inline-flex min-h-9 items-center gap-1.5 rounded-xl border border-brand-200 bg-brand-50 px-3 text-xs font-semibold text-brand-700">
                      <Bot size={13} />检索验证
                    </button>
                  </div>
                </div>
              </article>
            );
          })}
          {docs.length === 0 && <EmptyBlock>未找到匹配文献，请调整关键词。</EmptyBlock>}
        </div>
      </Card>

      <QaDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        initialQuestion={initialQuestion}
      />
    </div>
  );
}
