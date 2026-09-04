'use client';

import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Bot, FileSearch, Quote, Send, ShieldCheck, Sparkles, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { askAgri, toApiError } from '@/lib/http';
import { AGRI_QUICK_QUESTIONS } from '@/lib/demo';
import { newDemoRequestId } from '@/lib/format';
import { getTenant } from '@/lib/tenant';
import type { AgriCategory, Citation } from '@/lib/types';
import { Badge } from '@/components/ui/Badge';

interface QaDrawerProps {
  open: boolean;
  onClose: () => void;
  /** 打开时预填的问题（如点击某篇文档的「检索验证」） */
  initialQuestion?: string | null;
}

type Role = 'user' | 'assistant';

interface QaMessage {
  id: number;
  role: Role;
  content: string;
  error?: boolean;
  category?: AgriCategory;
  citations?: Citation[];
  disclaimer?: string | null;
  requestId?: string;
}

const CATEGORY_LABELS: Record<AgriCategory, string> = {
  DISEASE_PEST: '病虫害',
  FERTILIZER: '水肥',
  POLICY: '政策',
  GENERAL: '综合',
};

let msgSeq = 0;
function nextMsgId(): number {
  msgSeq += 1;
  return msgSeq;
}

/** 依据关键词粗判检索分类（用于 RAG 检索路由与展示） */
function inferCategory(q: string): AgriCategory {
  if (/病|虫|害|防治|纹枯|锈病|蚜虫|赤霉/.test(q)) return 'DISEASE_PEST';
  if (/肥|追肥|氮磷钾|返青|水肥|灌溉|浇水|叶面/.test(q)) return 'FERTILIZER';
  if (/政策|补贴|扶持|乡村振兴|保险|惠农/.test(q)) return 'POLICY';
  return 'GENERAL';
}

function similarityPct(s: number): string {
  const v = Math.max(0, Math.min(1, s));
  return `${(v * 100).toFixed(1)}%`;
}

/** 检索阶段状态提示（防幻觉链路：切片召回 → Top-K 排序 → 生成） */
function RetrievalHint({ pending }: { pending: boolean }) {
  const steps = ['切片召回', 'Top-K 排序', 'LLM 生成'];
  return (
    <div className="space-y-1.5 rounded-xl border border-brand-100 bg-brand-50/60 px-4 py-3">
      {steps.map((s, i) => (
        <div key={s} className="flex items-center gap-2 text-xs text-brand-700">
          {pending && i === 0 ? (
            <span className="h-3 w-3 animate-spin rounded-full border-2 border-brand-200 border-t-brand-600" />
          ) : (
            <span
              className={cn(
                'h-1.5 w-1.5 rounded-full',
                pending ? 'bg-brand-200' : 'bg-brand-400',
              )}
            />
          )}
          <span className={cn(!pending && i === 0 && 'font-semibold')}>{s}</span>
          {pending && i === 0 && <span className="animate-blink text-[10px]">检索中…</span>}
        </div>
      ))}
    </div>
  );
}

export function QaDrawer({ open, onClose, initialQuestion }: QaDrawerProps) {
  const [input, setInput] = useState('');
  const [pending, setPending] = useState(false);
  const [msgs, setMsgs] = useState<QaMessage[]>([]);
  const [started, setStarted] = useState(false);

  const sessionRef = useRef<string>(`${Date.now().toString(36)}`);
  const bodyRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const pendingRef = useRef(false);

  // 打开时：重置会话与消息，聚焦输入
  useEffect(() => {
    if (open) {
      sessionRef.current = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
      setMsgs([]);
      setPending(false);
      setStarted(false);
      if (initialQuestion) {
        setInput(initialQuestion);
      } else {
        setInput('');
      }
      window.setTimeout(() => inputRef.current?.focus(), 120);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  // 新消息自动滚到底部
  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [msgs, pending, open]);

  const tenant = useMemo(() => getTenant(), []);

  const ask = async (raw?: string) => {
    const text = (raw ?? input).trim();
    if (!text || pendingRef.current) return;
    setInput('');
    const category = inferCategory(text);
    const requestId = newDemoRequestId('qa');

    setMsgs((prev) => [
      ...prev,
      { id: nextMsgId(), role: 'user', content: text, category },
    ]);
    setStarted(true);
    setPending(true);

    try {
      const data = await askAgri({
        question: text,
        category,
        sessionId: sessionRef.current,
      });
      setMsgs((prev) => [
        ...prev,
        {
          id: nextMsgId(),
          role: 'assistant',
          content: data.answer,
          citations: data.citations ?? [],
          disclaimer: data.disclaimer,
          requestId,
        },
      ]);
    } catch (err) {
      const e = toApiError(err);
      setMsgs((prev) => [
        ...prev,
        {
          id: nextMsgId(),
          role: 'assistant',
          content: `${e.message}（${e.code}）`,
          error: true,
          requestId,
        },
      ]);
    } finally {
      setPending(false);
    }
  };

  const onKeyDown = (e: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void ask();
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-40">
      {/* 遮罩 */}
      <div className="absolute inset-0 bg-slate-900/35 backdrop-blur-[1px] animate-fade-in" onClick={onClose} />

      {/* 抽屉主体 */}
      <aside className="absolute inset-y-0 right-0 flex w-full max-w-[600px] flex-col bg-white shadow-2xl animate-slide-in-right">
        {/* 头部 */}
        <header className="flex items-start gap-3 border-b border-slate-100 px-5 py-4">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-50 text-violet-600">
            <Bot size={20} />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="flex items-center gap-2 text-[15px] font-semibold text-slate-800">
              农技 RAG 检索验证
              <Badge tone="violet">沙盒</Badge>
            </h3>
            <p className="mt-0.5 text-xs text-slate-400">
              知识库切片 Top-K 召回 → DeepSeek 生成 · 逐条附相似度与来源溯源
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="grid h-8 w-8 place-items-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
            aria-label="关闭"
          >
            <X size={18} />
          </button>
        </header>

        {/* 提示条 */}
        <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/70 px-5 py-2 text-[11px] text-slate-500">
          <ShieldCheck size={13} className="text-brand-600" />
          回答均基于租户“{tenant.name}”可见知识切片，仅当相似度达到阈值时给出结论（防幻觉）
        </div>

        {/* 消息区 */}
        <div ref={bodyRef} className="scrollbar-thin min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
          {!started && (
            <div className="space-y-3">
              <p className="text-xs text-slate-400">试试快速问题，或在上方输入框发起检索：</p>
              <div className="flex flex-wrap gap-2">
                {AGRI_QUICK_QUESTIONS.map((q) => (
                  <button
                    key={q}
                    type="button"
                    onClick={() => void ask(q)}
                    className="rounded-full border border-brand-200 bg-brand-50/60 px-3 py-1.5 text-xs text-brand-700 transition hover:border-brand-300 hover:bg-brand-100"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}

          {msgs.map((m) =>
            m.role === 'user' ? (
              <div key={m.id} className="flex justify-end gap-2">
                <div className="max-w-[85%]">
                  {m.category && (
                    <p className="mb-1 text-right text-[10px] text-slate-400">
                      分类：{CATEGORY_LABELS[m.category]}
                    </p>
                  )}
                  <div className="rounded-2xl rounded-br-md bg-brand-700 px-4 py-2.5 text-sm leading-6 text-white shadow-sm">
                    {m.content}
                  </div>
                </div>
              </div>
            ) : (
              <div key={m.id} className="flex items-start gap-2">
                <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-violet-50 text-violet-600">
                  <Sparkles size={14} />
                </span>
                <div className="min-w-0 max-w-[92%] flex-1 space-y-2">
                  {m.error ? (
                    <div className="rounded-2xl rounded-tl-md border border-red-100 bg-red-50 px-4 py-3 text-sm leading-6 text-red-600">
                      {m.content}
                    </div>
                  ) : (
                    <>
                      <div className="whitespace-pre-wrap rounded-2xl rounded-tl-md border border-slate-100 bg-white px-4 py-3 text-sm leading-6 text-slate-700 shadow-sm">
                        {m.content}
                      </div>

                      {m.citations && m.citations.length > 0 && (
                        <div>
                          <p className="mb-1.5 flex items-center gap-1.5 text-[11px] font-medium text-slate-400">
                            <FileSearch size={12} />
                            引用溯源 · Top-{m.citations.length}
                          </p>
                          <ul className="space-y-2">
                            {m.citations.map((c, i) => (
                              <li
                                key={`${c.docTitle}-${i}`}
                                className="rounded-xl border border-slate-200 bg-slate-50/60 p-3"
                              >
                                <div className="mb-1 flex flex-wrap items-center gap-2">
                                  <span className="text-xs font-semibold text-slate-700">
                                    {i + 1}. {c.docTitle}
                                  </span>
                                  <Badge tone={c.similarityScore >= 0.8 ? 'green' : c.similarityScore >= 0.6 ? 'amber' : 'slate'}>
                                    相似度 {similarityPct(c.similarityScore)}
                                  </Badge>
                                  {c.pageNumber != null && (
                                    <span className="text-[10px] text-slate-400">P{c.pageNumber}</span>
                                  )}
                                </div>
                                <p className="flex items-start gap-1 text-xs leading-5 text-slate-500">
                                  <Quote size={11} className="mt-0.5 shrink-0 text-slate-300" />
                                  <span className="line-clamp-3">{c.chunkText}</span>
                                </p>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {m.disclaimer && (
                        <p className="text-[11px] italic leading-5 text-slate-400">{m.disclaimer}</p>
                      )}
                      {m.requestId && (
                        <p className="num text-right text-[10px] text-slate-300">trace: {m.requestId}</p>
                      )}
                    </>
                  )}
                </div>
              </div>
            ),
          )}

          {pending && (
            <div className="flex items-start gap-2">
              <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-violet-50 text-violet-600">
                <Sparkles size={14} />
              </span>
              <div className="w-[240px]">
                <RetrievalHint pending />
              </div>
            </div>
          )}
        </div>

        {/* 输入区 */}
        <footer className="border-t border-slate-100 p-4">
          <div className="flex items-end gap-2">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              rows={2}
              placeholder="输入农技问题，如：冬小麦纹枯病如何防治？"
              className="scrollbar-thin min-h-[52px] flex-1 resize-none rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm text-slate-700 outline-none transition placeholder:text-slate-300 focus:border-brand-400 focus:ring-2 focus:ring-brand-100"
            />
            <button
              type="button"
              onClick={() => void ask()}
              disabled={pending || !input.trim()}
              className="inline-flex h-[52px] items-center gap-1.5 rounded-xl bg-brand-700 px-4 text-sm font-medium text-white shadow-sm transition hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {pending ? (
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              ) : (
                <Send size={15} />
              )}
              检索
            </button>
          </div>
          <p className="mt-1.5 text-right text-[10px] text-slate-300">Enter 发送 · Shift + Enter 换行</p>
        </footer>
      </aside>
    </div>
  );
}
