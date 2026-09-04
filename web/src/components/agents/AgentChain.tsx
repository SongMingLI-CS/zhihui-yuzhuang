'use client';

import { type ReactNode } from 'react';
import {
  Check,
  ChevronRight,
  Lightbulb,
  Loader2,
  PenLine,
  ShieldCheck,
  UserCheck,
  Bot,
} from 'lucide-react';
import { cn } from '@/lib/cn';
import type { AgentThoughtNode } from '@/lib/types';

/** 依据 agent_name 对齐链路节点元信息（与 ai-service marketing_agents.py 对齐） */
const NODE_META: Record<string, { role: string; icon: ReactNode; cls: string }> = {
  TrendAgent: {
    role: '热点捕捉',
    icon: <Lightbulb size={18} />,
    cls: 'bg-amber-50 text-amber-600',
  },
  CopywriterAgent: {
    role: '文案生成',
    icon: <PenLine size={18} />,
    cls: 'bg-sky-50 text-sky-600',
  },
  ComplianceAgent: {
    role: '合规质检',
    icon: <ShieldCheck size={18} />,
    cls: 'bg-emerald-50 text-emerald-600',
  },
};

/** 人类节点（不在 API 返回中，前端追加的“人工复核”收尾节点） */
const HUMAN_META = {
  role: '人机协同收尾',
  icon: <UserCheck size={18} />,
  cls: 'bg-amber-50 text-amber-600',
};

interface AgentChainProps {
  nodes: AgentThoughtNode[];
  /** 已揭示（完成态）节点数：逐步递增形成“流水线推进”观感 */
  visibleCount: number;
  running: boolean;
  /** 人工复核是否已通过 */
  humanApproved?: boolean;
}

type NodeState = 'done' | 'active' | 'pending' | 'review';

function NodeCard({
  name,
  role,
  summary,
  icon,
  iconCls,
  state,
  index,
  last,
}: {
  name: string;
  role: string;
  summary: string;
  icon: ReactNode;
  iconCls: string;
  state: NodeState;
  index: number;
  last: boolean;
}) {
  const sub = (() => {
    switch (state) {
      case 'active':
        return '执行中，正在产出中间结果…';
      case 'pending':
        return '排队等待上游输出';
      case 'review':
        return summary || '等待运营负责人审批';
      default:
        return summary || '已完成输出';
    }
  })();

  return (
    <div className="flex w-full min-w-0 items-center">
      <div
        className={cn(
          'flex-1 rounded-2xl border p-3.5 transition-all duration-300',
          state === 'done' && 'border-brand-200 bg-brand-50/50',
          state === 'active' && 'border-gold-500 bg-amber-50 shadow-md',
          state === 'review' && 'border-gold-400/70 bg-amber-50/70',
          state === 'pending' && 'border-slate-200 bg-slate-50 opacity-70',
        )}
      >
        <div className="flex items-center gap-2.5">
          <span
            className={cn(
              'relative grid h-9 w-9 shrink-0 place-items-center rounded-xl',
              state === 'done' && iconCls,
              state === 'active' && 'bg-gold-500 text-white',
              state === 'review' && 'bg-amber-100 text-amber-600',
              state === 'pending' && 'bg-slate-100 text-slate-400',
            )}
          >
            {state === 'active' ? (
              <Loader2 size={17} className="animate-spin" />
            ) : (
              icon
            )}
            {state === 'done' && (
              <span className="absolute -right-1 -top-1 grid h-4 w-4 place-items-center rounded-full bg-brand-600 text-white">
                <Check size={10} strokeWidth={3} />
              </span>
            )}
            {state === 'review' && (
              <span className="absolute -right-0.5 -top-0.5 h-2.5 w-2.5 animate-pulse rounded-full bg-gold-500 ring-2 ring-white" />
            )}
          </span>
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-[13px] font-semibold text-slate-800">
              <span className="num text-[10px] text-slate-400">#{index + 1}</span>
              {name}
            </p>
            <p className="text-[11px] text-slate-400">{role}</p>
          </div>
        </div>
        <p
          className={cn(
            'mt-2.5 border-t pt-2 text-[11px] leading-5',
            state === 'pending' ? 'text-slate-300' : 'text-slate-500',
          )}
        >
          {sub}
        </p>
      </div>

      {/* 连接箭头 */}
      {!last && (
        <span className="shrink-0 px-1.5 text-slate-300">
          <ChevronRight size={16} />
        </span>
      )}
    </div>
  );
}

/** 多 Agent 协同链路流程图：TrendAgent → CopywriterAgent → ComplianceAgent → 人工复核 */
export function AgentChain({ nodes, visibleCount, running, humanApproved = false }: AgentChainProps) {
  const humanState: NodeState = running ? 'pending' : humanApproved ? 'done' : 'review';
  const humanSummary = running
    ? '等待多 Agent 产出初稿…'
    : humanApproved
      ? '运营负责人已审批通过，初稿已推送至私域运营工作台待发布'
      : 'AI 初稿已就绪，等待运营负责人审批（人机协同 · 先审后发）';

  return (
    <div className="flex flex-col gap-2">
      {/* 链路条 */}
      <div className="flex items-start gap-1 overflow-x-auto pb-1 scrollbar-thin">
        {nodes.map((n, i) => {
          const meta = NODE_META[n.agent_name] ?? {
            role: n.agent_name,
            icon: <Bot size={18} />,
            cls: 'bg-slate-100 text-slate-500',
          };
          const state: NodeState = i < visibleCount ? 'done' : i === visibleCount ? 'active' : 'pending';
          return (
            <NodeCard
              key={n.agent_name}
              name={n.agent_name}
              role={meta.role}
              summary={n.output_summary}
              icon={meta.icon}
              iconCls={meta.cls}
              state={state}
              index={i}
              last={false}
            />
          );
        })}
        <NodeCard
          name={humanApproved ? '已复核' : '人工复核'}
          role={HUMAN_META.role}
          summary={humanSummary}
          icon={HUMAN_META.icon}
          iconCls={HUMAN_META.cls}
          state={humanState}
          index={nodes.length}
          last
        />
      </div>

      {/* 运行提示 */}
      <div className="flex items-center gap-1.5 text-[11px] text-slate-400">
        <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5">
          {running ? (
            <>
              <Loader2 size={11} className="animate-spin text-gold-500" />
              多 Agent 协同生成中…
            </>
          ) : (
            <span className="inline-flex items-center gap-1">
              <span
                className={cn(
                  'h-1.5 w-1.5 rounded-full',
                  humanApproved ? 'bg-brand-500' : 'animate-blink bg-gold-500',
                )}
              />
              {humanApproved ? '已通过 · 待发布' : '链路就绪 · 待人工审批'}
            </span>
          )}
        </span>
        <span className="inline-flex items-center gap-1">
          <UserCheck size={11} />
          人机协同：AI 初稿 → 人工审批后发布
        </span>
      </div>
    </div>
  );
}
