'use client';

import { AlertTriangle, BadgeCheck, ShieldAlert, ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/cn';
import { Badge } from '@/components/ui/Badge';
import { HighlightTerms } from '@/components/agents/HighlightTerms';
import { partitionRiskTerms, RISK_KIND_LABEL } from '@/lib/compliance';
import type { ComplianceReport } from '@/lib/types';

const RADIUS = 54;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

function gaugeColor(score: number): string {
  if (score >= 90) return '#2c724a'; // brand-600 治理绿
  if (score >= 75) return '#d9a52e'; // gold-500 提示金
  return '#dc2626'; // red-600 预警红
}

interface CompliancePanelProps {
  compliance: ComplianceReport;
  /** 产品名称（输入源，用于命中词二次高亮定位） */
  sourceName?: string;
  /** 卖点列表（输入源，用于命中词二次高亮定位） */
  sourcePoints?: string[];
}

/** 广告法合规质检报告（ComplianceAgent 输出可视化） */
export function CompliancePanel({ compliance, sourceName, sourcePoints = [] }: CompliancePanelProps) {
  const score = Math.max(0, Math.min(100, compliance.score));
  const color = gaugeColor(score);
  const offset = CIRCUMFERENCE * (1 - score / 100);
  const groups = partitionRiskTerms(compliance.risk_terms_detected);
  const totalHits = compliance.risk_terms_detected.length;

  const sourceTexts = [sourceName, ...sourcePoints].filter((t): t is string => Boolean(t));

  return (
    <div className="flex flex-col gap-5 lg:flex-row">
      {/* 左侧：评分仪表 */}
      <div className="flex shrink-0 items-center gap-5 lg:w-[240px] lg:flex-col lg:gap-3">
        <div className="relative h-[140px] w-[140px] shrink-0">
          <svg viewBox="0 0 140 140" className="h-full w-full -rotate-90">
            <circle
              cx="70"
              cy="70"
              r={RADIUS}
              fill="none"
              stroke="#eef1ec"
              strokeWidth="12"
            />
            <circle
              cx="70"
              cy="70"
              r={RADIUS}
              fill="none"
              stroke={color}
              strokeWidth="12"
              strokeLinecap="round"
              strokeDasharray={CIRCUMFERENCE}
              strokeDashoffset={offset}
              style={{ transition: 'stroke-dashoffset 0.7s ease-out, stroke 0.3s' }}
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <p className="num text-[34px] font-bold leading-none" style={{ color }}>
              {score}
            </p>
            <p className="mt-1 text-[10px] tracking-wide text-slate-400">合规评分 / 100</p>
          </div>
        </div>

        {/* 区间图例 */}
        <div className="flex items-center gap-3 lg:flex-col lg:items-start lg:gap-1">
          <LegendDot color="#2c724a" label="≥90 安全" />
          <LegendDot color="#d9a52e" label="75–89 提示" />
          <LegendDot color="#dc2626" label="<75 预警" />
        </div>
      </div>

      {/* 右侧：结论 + 敏感词 + 整改建议 */}
      <div className="min-w-0 flex-1 space-y-4">
        {/* 结论行 */}
        <div className="flex flex-wrap items-center gap-2">
          {compliance.passed ? (
            <Badge tone="green" dot>
              <BadgeCheck size={12} />
              检测通过 · 未命中广告法敏感词
            </Badge>
          ) : (
            <Badge tone="red" dot>
              <ShieldAlert size={12} />
              检测未通过 · 命中 {totalHits} 处敏感词，需整改后人工复核
            </Badge>
          )}
          <span className="inline-flex items-center gap-1 text-[11px] text-slate-400">
            <ShieldCheck size={12} />
            判定依据：ComplianceAgent 确定性规则引擎（不依赖 LLM）
          </span>
        </div>

        {/* 敏感词分组 */}
        {totalHits > 0 ? (
          <div className="space-y-2.5">
            {(['medical', 'absolute'] as const).map((kind) => {
              const terms = groups[kind];
              if (!terms.length) return null;
              const isMedical = kind === 'medical';
              return (
                <div key={kind} className="flex flex-wrap items-start gap-1.5">
                  <span
                    className={cn(
                      'mt-px inline-flex items-center gap-1 text-[11px] font-medium',
                      isMedical ? 'text-red-500' : 'text-amber-600',
                    )}
                  >
                    <AlertTriangle size={12} />
                    {RISK_KIND_LABEL[kind]}：
                  </span>
                  {terms.map((t) => (
                    <span
                      key={t}
                      className={cn(
                        'rounded-md border px-1.5 py-0.5 text-[11px] font-medium',
                        isMedical
                          ? 'border-red-200 bg-red-50 text-red-600'
                          : 'border-amber-200 bg-amber-50 text-amber-700',
                      )}
                    >
                      「{t}」
                    </span>
                  ))}
                </div>
              );
            })}
          </div>
        ) : (
          <p className="text-xs text-slate-400">
            全量扫描产品名、卖点与三端文案标题/正文/CTA，均未命中风险表述。
          </p>
        )}

        {/* 输入源命中定位（仅未通过时展示） */}
        {!compliance.passed && sourceTexts.length > 0 && (
          <div className="rounded-xl border border-red-100 bg-red-50/40 p-3">
            <p className="mb-2 flex items-center gap-1 text-[11px] font-semibold text-red-500">
              <ShieldAlert size={12} />
              输入源命中定位（供整改参考）
            </p>
            <ul className="space-y-1 text-xs text-slate-600">
              {sourceTexts.map((text, i) => (
                <li key={i} className="flex gap-1.5">
                  <span className="mt-0.5 shrink-0 rounded bg-red-100 px-1 text-[10px] text-red-500">
                    {i === 0 ? '品名' : `卖点${i}`}
                  </span>
                  <HighlightTerms text={text} terms={compliance.risk_terms_detected} />
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* 整改建议 */}
        <div>
          <p className="mb-1.5 text-[11px] font-semibold text-slate-500">
            {compliance.passed ? '质检备注' : 'ComplianceAgent 整改建议'}
          </p>
          <ul className="space-y-1.5">
            {(compliance.revision_suggestions?.length
              ? compliance.revision_suggestions
              : ['未命中敏感词，文案可进入人工复核环节。']
            ).map((s, i) => (
              <li
                key={i}
                className="flex items-start gap-2 rounded-lg border border-slate-100 bg-slate-50/60 px-3 py-2 text-xs leading-5 text-slate-600"
              >
                <span className="mt-0.5 shrink-0 rounded bg-brand-100 px-1 text-[10px] text-brand-700">
                  {String(i + 1).padStart(2, '0')}
                </span>
                <span>{s}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

function LegendDot({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-[10px] text-slate-400">
      <span className="h-2 w-2 rounded-full" style={{ backgroundColor: color }} />
      {label}
    </span>
  );
}
