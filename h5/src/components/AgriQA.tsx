import { useEffect, useRef, useState } from 'react';
import { Bot, Loader2, MessageCircle, Send, Sparkles, X } from 'lucide-react';
import type { Citation } from '../types/api';
import { askAgri, toApiError } from '../lib/http';
import { AGRI_QUICK_QUESTIONS } from '../config';
import { useToast } from './Toast';

interface QAExchange {
  role: 'user' | 'ai';
  text: string;
  citations?: Citation[];
}

function genSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `qa-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** 右下角「豫农智汇」农技问答悬浮球 + 溯源问答抽屉（POST /ai/v1/qa/ask） */
export default function AgriQA() {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [messages, setMessages] = useState<QAExchange[]>([]);
  const sessionRef = useRef<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  // 锁背景滚动
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // 新消息/回答中自动滚到底
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, loading]);

  const toggleOpen = () => setOpen((v) => !v);

  const ask = async (raw: string) => {
    const question = raw.trim();
    if (!question || loading) return;
    const sessionId =
      sessionRef.current ?? (sessionRef.current = genSessionId());
    setMessages((prev) => [...prev, { role: 'user', text: question }]);
    setInput('');
    setLoading(true);
    try {
      const res = await askAgri({ question, sessionId });
      setMessages((prev) => [
        ...prev,
        { role: 'ai', text: res.answer, citations: res.citations },
      ]);
    } catch (err) {
      const apiErr = toApiError(err);
      toast('error', apiErr.message || 'AI 农技服务暂时不可用，请稍后再试');
    } finally {
      setLoading(false);
    }
  };

  const quickAsk = (q: string) => ask(q);

  return (
    <>
      {/* 悬浮球 */}
      {!open && (
        <button
          type="button"
          onClick={toggleOpen}
          className="fixed bottom-[calc(env(safe-area-inset-bottom)+18px)] right-4 z-[60] flex items-center gap-2 rounded-full bg-gradient-to-r from-green-700 to-emerald-600 py-2 pl-2 pr-3.5 text-white shadow-xl shadow-green-800/30 active:scale-95"
        >
          <span className="relative grid h-9 w-9 place-items-center rounded-full bg-white/15 ring-1 ring-white/25">
            <Bot size={20} />
            <span className="absolute -right-0.5 -top-0.5 animate-wiggle rounded-full bg-amber-400 px-1 text-[8px] font-bold text-amber-900">
              AI
            </span>
          </span>
          <span className="text-[13px] font-bold leading-none">豫农智汇</span>
          <span className="rounded bg-white/20 px-1.5 py-0.5 text-[9px] font-medium text-green-50">
            农技咨询
          </span>
        </button>
      )}

      {/* 问答抽屉 */}
      {open && (
        <div className="fixed inset-0 z-[70]">
          <div
            className="absolute inset-0 animate-fade-in bg-slate-900/50"
            onClick={toggleOpen}
          />
          <div className="absolute inset-x-0 bottom-0 mx-auto flex max-h-[84vh] w-full max-w-[430px] animate-slide-up flex-col overflow-hidden rounded-t-3xl bg-white safe-bottom">
            {/* 头部 */}
            <div className="flex items-center justify-between bg-gradient-to-r from-green-700 to-emerald-600 px-5 py-4 text-white">
              <div className="flex items-center gap-2.5">
                <span className="grid h-9 w-9 place-items-center rounded-full bg-white/15 ring-1 ring-white/25">
                  <Sparkles size={17} />
                </span>
                <div>
                  <h2 className="text-[15px] font-bold">豫农智汇 · 农技AI助手</h2>
                  <p className="text-[10px] text-green-100/90">基于《于庄小麦种植指南》等资料 · 回答可溯源</p>
                </div>
              </div>
              <button
                type="button"
                onClick={toggleOpen}
                className="grid h-8 w-8 place-items-center rounded-full bg-white/15 active:scale-95"
                aria-label="关闭"
              >
                <X size={16} />
              </button>
            </div>

            {/* 消息区 */}
            <div className="min-h-0 flex-1 space-y-3 overflow-y-auto bg-[#f4f6ef] px-4 py-4">
              {messages.length === 0 && !loading && (
                <div className="rounded-2xl bg-white p-4 ring-1 ring-slate-100">
                  <div className="flex items-center gap-2 text-[13px] font-bold text-slate-700">
                    <MessageCircle size={15} className="text-green-600" />
                    有什么农技问题？快速提问：
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {AGRI_QUICK_QUESTIONS.map((q) => (
                      <button
                        key={q}
                        type="button"
                        onClick={() => quickAsk(q)}
                        className="rounded-full border border-green-200 bg-green-50 px-3 py-1.5 text-[12px] text-green-700 active:scale-95"
                      >
                        {q}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {messages.map((m, i) =>
                m.role === 'user' ? (
                  <div key={i} className="flex justify-end">
                    <div className="max-w-[82%] rounded-2xl rounded-br-md bg-green-600 px-3.5 py-2.5 text-[13px] leading-relaxed text-white shadow-sm">
                      {m.text}
                    </div>
                  </div>
                ) : (
                  <div key={i} className="flex items-start gap-2">
                    <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full bg-green-600 text-white">
                      <Bot size={15} />
                    </span>
                    <div className="max-w-[85%] space-y-2">
                      <div className="whitespace-pre-wrap break-words rounded-2xl rounded-tl-md bg-white px-3.5 py-2.5 text-[13px] leading-relaxed text-slate-700 shadow-sm">
                        {m.text}
                      </div>

                      {m.citations && m.citations.length > 0 && (
                        <div className="rounded-2xl bg-amber-50/80 p-3 ring-1 ring-amber-100">
                          <p className="text-[10px] font-semibold text-amber-600">参考资料（防幻觉溯源）</p>
                          <div className="mt-1.5 space-y-1.5">
                            {m.citations.map((c, j) => (
                              <div key={j} className="text-[11px] leading-snug text-slate-500">
                                <span className="font-semibold text-slate-600">
                                  {c.docTitle}
                                  {c.pageNumber != null ? ` P${c.pageNumber}` : ''}
                                </span>
                                <span className="ml-1 text-amber-500">
                                  相关度 {Math.round(c.similarityScore * 100)}%
                                </span>
                                <p className="mt-0.5 line-clamp-2 text-slate-400">{c.chunkText}</p>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                ),
              )}

              {loading && (
                <div className="flex items-start gap-2">
                  <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full bg-green-600 text-white">
                    <Bot size={15} />
                  </span>
                  <div className="flex items-center gap-2 rounded-2xl rounded-tl-md bg-white px-3.5 py-2.5 text-[12px] text-slate-400 shadow-sm">
                    <Loader2 size={14} className="animate-spin text-green-600" />
                    正在查阅农技资料，请稍候…
                  </div>
                </div>
              )}
              <div ref={endRef} />
            </div>

            {/* 输入区 */}
            <div className="border-t border-slate-100 bg-white px-4 py-3">
              <div className="flex items-end gap-2">
                <textarea
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      void ask(input);
                    }
                  }}
                  rows={1}
                  placeholder="输入农技问题，如：冬小麦发黄怎么办？"
                  maxLength={300}
                  className="max-h-24 min-h-[40px] w-full flex-1 resize-none rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[13px] leading-relaxed placeholder:text-slate-300 focus:border-green-500 focus:bg-white focus:ring-2 focus:ring-green-100"
                />
                <button
                  type="button"
                  disabled={loading || !input.trim()}
                  onClick={() => void ask(input)}
                  className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-gradient-to-r from-green-600 to-emerald-500 text-white shadow-md shadow-green-600/20 active:scale-95 disabled:opacity-40"
                  aria-label="发送"
                >
                  <Send size={16} />
                </button>
              </div>
              <p className="mt-1.5 text-center text-[9px] text-slate-300">
                AI 回答仅供参考，关键决策请咨询当地农技站
              </p>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
