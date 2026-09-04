'use client';

import { useMemo, useState } from 'react';
import {
  BookOpenCheck,
  Bot,
  FileText,
  Info,
  Layers,
  Library,
  MessageSquareText,
  Search,
} from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { QaDrawer } from '@/components/knowledge/QaDrawer';
import { AGRI_QUICK_QUESTIONS, DEMO_NOTE, KNOWLEDGE_DOCS, type DocStatus } from '@/lib/demo';
import { formatInt } from '@/lib/format';

const STATUS_META: Record<DocStatus, { label: string; tone: 'green' | 'amber' | 'red' }> = {
  READY: { label: '已就绪', tone: 'green' },
  PROCESSING: { label: '切片中', tone: 'amber' },
  FAILED: { label: '失败', tone: 'red' },
};

/** 点击某篇文档 → 预填针对该文档的检索问题 */
const SUGGEST: Record<number, string> = {
  1: '冬小麦播种期的关键技术要点有哪些？',
  2: '小麦常见真菌病（纹枯病等）如何防治？',
  3: '冬小麦返青期如何追肥与浇水？',
  4: '河南省乡村振兴特色产业有哪些扶持政策？',
  5: '小磨香油的传统制作工艺是怎样的？',
};

export default function KnowledgePage() {
  const [query, setQuery] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [initialQuestion, setInitialQuestion] = useState<string | null>(null);

  const totalChunks = useMemo(
    () => KNOWLEDGE_DOCS.reduce((s, d) => s + d.chunks, 0),
    [],
  );

  const docs = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return KNOWLEDGE_DOCS;
    return KNOWLEDGE_DOCS.filter(
      (d) =>
        d.title.toLowerCase().includes(q) ||
        d.tenantName.toLowerCase().includes(q) ||
        d.source.toLowerCase().includes(q),
    );
  }, [query]);

  const openVerify = (docId: number) => {
    setInitialQuestion(SUGGEST[docId] ?? AGRI_QUICK_QUESTIONS[0]);
    setDrawerOpen(true);
  };

  const openEmpty = () => {
    setInitialQuestion(null);
    setDrawerOpen(true);
  };

  return (
    <div className="flex flex-col gap-5 p-5">
      {/* 页头 */}
      <div className="flex flex-wrap items-center gap-3">
        <div>
          <h2 className="text-lg font-bold text-slate-800">农技知识库沙盒</h2>
          <p className="mt-0.5 text-xs text-slate-400">
            已切片文献检索 · 附 Top-K 引用溯源（相似度 / 来源 / 页码）
          </p>
        </div>
        <Badge tone="amber" className="ml-auto hidden md:inline-flex">
          <Info size={12} />
          {DEMO_NOTE}
        </Badge>
      </div>

      {/* 概览条 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Card padded className="sm:col-span-1">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-brand-50 text-brand-600">
              <Library size={18} />
            </span>
            <div>
              <p className="num text-xl font-bold text-slate-800">{KNOWLEDGE_DOCS.length} 篇</p>
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
            <div className="relative hidden md:block">
              <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-300" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="搜索标题 / 租户 / 来源"
                className="h-8 w-52 rounded-lg border border-slate-200 bg-white pl-8 pr-2 text-xs text-slate-600 outline-none transition placeholder:text-slate-300 focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
              />
            </div>
            <button
              type="button"
              onClick={openEmpty}
              className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-brand-700 px-3 text-xs font-medium text-white shadow-sm transition hover:bg-brand-800"
            >
              <Bot size={14} />
              农技检索验证
            </button>
          </div>
        }
        bodyClassName="p-0"
      >
        <div className="scrollbar-thin overflow-x-auto">
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
                        onClick={() => openVerify(d.id)}
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
      </Card>

      <QaDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        initialQuestion={initialQuestion}
      />
    </div>
  );
}
