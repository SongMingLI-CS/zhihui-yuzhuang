'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { BookOpen, Check, Copy, MessageCircle, Mic2, ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/cn';
import { Badge } from '@/components/ui/Badge';
import { HighlightTerms } from '@/components/agents/HighlightTerms';
import { CHANNEL_LABELS } from '@/lib/tenant';
import type { MarketingChannel, MarketingCopyItem } from '@/lib/types';

const CHANNEL_ICON: Record<MarketingChannel, ReactNode> = {
  MOMENTS: <MessageCircle size={15} />,
  RED_BOOK: <BookOpen size={15} />,
  LIVESTREAM: <Mic2 size={15} />,
};

const CHANNEL_DESC: Record<MarketingChannel, string> = {
  MOMENTS: '私域朋友圈 · 熟人信任背书',
  RED_BOOK: '种草笔记 · 场景化内容图文',
  LIVESTREAM: '直播间口播 · 快节奏促单话术',
};

interface CopyPreviewProps {
  copies: MarketingCopyItem[];
  /** 待复核敏感词（服务端已自动脱敏，此处用于残留二次高亮） */
  riskTerms?: string[];
}

/** 分渠道文案矩阵预览（Tab 切换 + 一键复制） */
export function CopyPreview({ copies, riskTerms = [] }: CopyPreviewProps) {
  const [active, setActive] = useState<MarketingChannel | null>(copies[0]?.channel ?? null);
  const [copied, setCopied] = useState<boolean>(false);

  const activeItem = useMemo(
    () => copies.find((c) => c.channel === active) ?? copies[0] ?? null,
    [copies, active],
  );

  const copyActive = async () => {
    if (!activeItem) return;
    const payload = `${activeItem.title}\n\n${activeItem.content}\n\n${activeItem.call_to_action}`;
    try {
      await navigator.clipboard.writeText(payload);
    } catch {
      // 非安全上下文降级：无剪贴板权限时静默
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  };

  if (!copies.length) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 px-6 py-12 text-center text-sm text-slate-400">
        暂无生成的文案
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* 渠道 Tab */}
      <div className="scrollbar-thin flex gap-2 overflow-x-auto pb-1">
        {copies.map((c) => {
          const isActive = activeItem?.channel === c.channel;
          return (
            <button
              key={c.channel}
              type="button"
              onClick={() => setActive(c.channel)}
              className={cn(
                'inline-flex shrink-0 items-center gap-2 rounded-xl border px-3.5 py-2.5 text-left transition',
                isActive
                  ? 'border-brand-300 bg-brand-50 shadow-sm'
                  : 'border-slate-200 bg-white hover:border-brand-200 hover:bg-brand-50/40',
              )}
            >
              <span
                className={cn(
                  'grid h-7 w-7 place-items-center rounded-lg',
                  isActive ? 'bg-brand-600 text-white' : 'bg-slate-100 text-slate-500',
                )}
              >
                {CHANNEL_ICON[c.channel] ?? <MessageCircle size={15} />}
              </span>
              <span>
                <span className="flex items-center gap-1.5 text-[13px] font-semibold text-slate-700">
                  {CHANNEL_LABELS[c.channel]}
                </span>
                <span className="block text-[10px] text-slate-400">{CHANNEL_DESC[c.channel]}</span>
              </span>
            </button>
          );
        })}
      </div>

      {/* 当前渠道文案卡片 */}
      <div className="rounded-2xl border border-slate-200 bg-white">
        <div className="flex items-center justify-between gap-2 border-b border-slate-100 px-4 py-2.5">
          <div className="flex items-center gap-2 text-[11px] text-slate-400">
            <ShieldCheck size={12} className="text-brand-500" />
            AI 初稿 · 已通过 ComplianceAgent 自动脱敏，请运营复核后发布
          </div>
          <button
            type="button"
            onClick={copyActive}
            className={cn(
              'inline-flex h-7 items-center gap-1.5 rounded-lg border px-2.5 text-[11px] font-medium transition',
              copied
                ? 'border-brand-200 bg-brand-50 text-brand-700'
                : 'border-slate-200 bg-white text-slate-500 hover:border-brand-300 hover:text-brand-700',
            )}
          >
            {copied ? <Check size={13} /> : <Copy size={13} />}
            {copied ? '已复制整篇' : '复制文案'}
          </button>
        </div>

        {activeItem && (
          <div className="space-y-3 p-4">
            {/* 标题 */}
            <p className="text-[15px] font-bold leading-6 text-slate-800">
              <HighlightTerms text={activeItem.title} terms={riskTerms} />
            </p>

            {/* 正文 */}
            <div className="rounded-xl bg-slate-50/70 px-4 py-3">
              <p className="whitespace-pre-line text-[13px] leading-6 text-slate-600">
                <HighlightTerms text={activeItem.content} terms={riskTerms} />
              </p>
            </div>

            {/* CTA */}
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="slate">行动号召 CTA</Badge>
              <span className="inline-flex items-center gap-1 rounded-lg bg-brand-700 px-3 py-1 text-xs font-medium text-white">
                <HighlightTerms text={activeItem.call_to_action} terms={riskTerms} />
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
