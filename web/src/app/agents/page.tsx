'use client';

import { useEffect, useRef, useState } from 'react';
import {
  BadgeCheck,
  Bot,
  Check,
  Info,
  Loader2,
  RefreshCw,
  Send,
  ShieldCheck,
  Sparkles,
  Wand2,
} from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { PageHeader } from '@/components/ui/PageHeader';
import { useToast } from '@/components/ui/Toast';
import { AgentChain } from '@/components/agents/AgentChain';
import { CopyPreview } from '@/components/agents/CopyPreview';
import { CompliancePanel } from '@/components/agents/CompliancePanel';
import { PRODUCT_PRESETS } from '@/lib/demo';
import { generateMarketing, toApiError } from '@/lib/http';
import { CHANNEL_LABELS } from '@/lib/tenant';
import { newDemoRequestId } from '@/lib/format';
import type {
  AgentThoughtNode,
  MarketingChannel,
  MarketingGenerateResponse,
} from '@/lib/types';

/** ai-service 固定编排链路（与 marketing_service.py 对齐，用于运行期骨架展示） */
const CHAIN_AGENT_NAMES = ['TrendAgent', 'CopywriterAgent', 'ComplianceAgent'];
const ALL_CHANNELS: MarketingChannel[] = ['MOMENTS', 'RED_BOOK', 'LIVESTREAM'];

const inputCls =
  'control w-full px-3 py-2 text-[13px] text-slate-700 placeholder:text-slate-400 disabled:bg-slate-50';

export default function AgentsPage() {
  const { notify } = useToast();
  const productRef = useRef<HTMLInputElement>(null);
  const pointsRef = useRef<HTMLTextAreaElement>(null);
  // 表单
  const [productName, setProductName] = useState('');
  const [pointsText, setPointsText] = useState('');
  const [audience, setAudience] = useState('');
  const [channels, setChannels] = useState<MarketingChannel[]>([...ALL_CHANNELS]);

  // 运行态
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [chainNodes, setChainNodes] = useState<AgentThoughtNode[]>([]);
  const [visibleCount, setVisibleCount] = useState(0);
  const [result, setResult] = useState<MarketingGenerateResponse | null>(null);
  const [approved, setApproved] = useState(false);
  const [requestId, setRequestId] = useState<string | null>(null);

  const sellingPoints = pointsText
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  const nameLen = productName.trim().length;
  const nameOk = nameLen >= 2 && nameLen <= 50;
  const pointsOk = sellingPoints.length > 0;

  // 运行期间流水线推进（真实请求为一次阻塞式调用，前端按固定链路做揭示动画）
  useEffect(() => {
    if (!running) return;
    setVisibleCount(0);
    const id = window.setInterval(() => {
      setVisibleCount((c) => Math.min(c + 1, CHAIN_AGENT_NAMES.length));
    }, 620);
    return () => window.clearInterval(id);
  }, [running]);

  const skeletonChain = (): AgentThoughtNode[] =>
    CHAIN_AGENT_NAMES.map((name) => ({ agent_name: name, output_summary: '' }));

  const applyPreset = (p: (typeof PRODUCT_PRESETS)[number]) => {
    setProductName(p.name);
    setPointsText(p.points.join('\n'));
    setAudience(p.audience);
    setResult(null);
    setApproved(false);
    setError(null);
  };

  const toggleChannel = (c: MarketingChannel) => {
    setChannels((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : [...prev, c]));
  };

  const generate = async () => {
    setError(null);
    if (!nameOk) {
      setError('产品名称需为 2–50 个字符');
      productRef.current?.focus();
      return;
    }
    if (!pointsOk) {
      setError('请至少填写 1 条卖点（每行一条）');
      pointsRef.current?.focus();
      return;
    }
    if (channels.length === 0) {
      setError('请至少选择一个发布渠道');
      return;
    }

    setApproved(false);
    setResult(null);
    setRunning(true);
    setChainNodes(skeletonChain());
    setVisibleCount(0);
    const rid = newDemoRequestId('mktg');
    setRequestId(rid);

    try {
      const data = await generateMarketing({
        product_name: productName.trim(),
        selling_points: sellingPoints,
        target_audience: audience.trim() || null,
        channel_preferences: channels,
      });
      setChainNodes(data.thought_chain);
      setVisibleCount(data.thought_chain.length);
      setResult(data);
      notify('success', '营销初稿已生成', '请继续检查分渠道文案与合规报告。');
    } catch (err) {
      setError(toApiError(err).message);
    } finally {
      setRunning(false);
    }
  };

  const rejectRegenerate = () => {
    void generate();
  };

  const approve = () => {
    setApproved(true);
    notify('success', '审批已通过', '文案已模拟同步至私域运营工作台。');
  };

  const displayNodes = running ? chainNodes : result ? result.thought_chain : [];
  const displayVisible = running ? visibleCount : result ? result.thought_chain.length : 0;
  const compliance = result?.compliance ?? null;
  const hasResult = result != null;

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="AI MARKETING STUDIO"
        title="营销 Agent 协同台"
        description="从产品卖点到分渠道文案、合规检查与人工放行，让生成过程清晰可控。"
        actions={<Badge tone="violet"><Bot size={12} />多智能体编排</Badge>}
      />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-12 xl:gap-5">
        {/* ============ 左：生成表单 ============ */}
        <div className="xl:col-span-4">
          <Card
            icon={<Wand2 size={16} />}
            title="生成需求"
            subtitle="填写特产信息，AI 将按渠道生成差异化初稿"
            className="xl:sticky xl:top-5"
          >
            <div className="space-y-4">
              {/* 快捷示例 */}
              <div>
                <p className="mb-1.5 text-xs font-medium text-slate-500">快捷示例</p>
                <div className="flex flex-wrap gap-1.5">
                  {PRODUCT_PRESETS.map((p) => (
                    <button
                      key={p.name}
                      type="button"
                      onClick={() => applyPreset(p)}
                      disabled={running}
                      className="inline-flex items-center rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] text-slate-600 transition hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {p.name}
                    </button>
                  ))}
                </div>
              </div>

              {/* 产品名称 */}
              <div>
                <label htmlFor="product-name" className="mb-1.5 flex items-center justify-between text-xs font-semibold text-slate-600">
                  <span>产品名称</span>
                  <span className={`num ${nameOk ? 'text-brand-500' : 'text-slate-300'}`}>
                    {nameLen}/50
                  </span>
                </label>
                <input
                  ref={productRef}
                  id="product-name"
                  value={productName}
                  onChange={(e) => setProductName(e.target.value)}
                  placeholder="例：于庄传统石磨小磨香油"
                  className={inputCls}
                  disabled={running}
                  maxLength={50}
                  aria-invalid={error?.startsWith('产品名称') || undefined}
                  aria-describedby={error?.startsWith('产品名称') ? 'agent-form-error' : undefined}
                />
              </div>

              {/* 核心卖点 */}
              <div>
                <label htmlFor="selling-points" className="mb-1.5 flex items-center justify-between text-xs font-semibold text-slate-600">
                  <span>核心卖点</span>
                  <span className="text-slate-300">每行一条</span>
                </label>
                <textarea
                  ref={pointsRef}
                  id="selling-points"
                  value={pointsText}
                  onChange={(e) => setPointsText(e.target.value)}
                  placeholder={'古法石磨初榨\n无任何添加剂\n芝麻原香浓郁'}
                  rows={4}
                  className={`${inputCls} resize-none leading-5`}
                  disabled={running}
                  aria-invalid={error?.startsWith('请至少填写') || undefined}
                  aria-describedby={error?.startsWith('请至少填写') ? 'agent-form-error' : undefined}
                />
              </div>

              {/* 目标人群 */}
              <div>
                <label htmlFor="target-audience" className="mb-1.5 block text-xs font-semibold text-slate-600">
                  目标人群（可选）
                </label>
                <input
                  id="target-audience"
                  value={audience}
                  onChange={(e) => setAudience(e.target.value)}
                  placeholder="例：注重食品健康的家庭主妇"
                  className={inputCls}
                  disabled={running}
                />
              </div>

              {/* 渠道选择 */}
              <div>
                <p className="mb-1.5 text-xs font-semibold text-slate-600">发布渠道</p>
                <div className="flex flex-wrap gap-1.5" role="group" aria-label="发布渠道">
                  {ALL_CHANNELS.map((c) => {
                    const on = channels.includes(c);
                    return (
                      <button
                        key={c}
                        type="button"
                        onClick={() => toggleChannel(c)}
                        disabled={running}
                        role="checkbox"
                        aria-checked={on}
                        className={`inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${
                          on
                            ? 'border-brand-300 bg-brand-50 text-brand-700'
                            : 'border-slate-200 bg-white text-slate-400 hover:border-brand-200'
                        }`}
                      >
                        <span
                          className={`grid h-3.5 w-3.5 place-items-center rounded border ${
                            on ? 'border-brand-600 bg-brand-600 text-white' : 'border-slate-300'
                          }`}
                        >
                          {on && <Check size={10} strokeWidth={3} />}
                        </span>
                        {CHANNEL_LABELS[c]}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* 生成按钮 */}
              <div className="pt-1">
                <button
                  type="button"
                  onClick={() => void generate()}
                  disabled={running}
                  className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-brand-700 px-4 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-800 disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {running ? (
                    <>
                      <Loader2 size={15} className="animate-spin" />
                      多 Agent 协同生成中…
                    </>
                  ) : (
                    <>
                      <Sparkles size={15} />
                      开始生成 · 分渠道初稿
                    </>
                  )}
                </button>
                <p className="mt-2 text-center text-[10px] leading-4 text-slate-400">
                  DeepSeek 在线约 10–60s，离线模板秒级返回
                  {requestId && (
                    <span className="num ml-1 text-slate-300">· trace {requestId}</span>
                  )}
                </p>
              </div>

              {error && (
                <div id="agent-form-error" role="alert" className="rounded-xl border border-red-200 bg-red-50 px-3 py-2.5 text-xs font-medium text-red-700">
                  {error}
                </div>
              )}
            </div>
          </Card>
        </div>

        {/* ============ 右：结果区 ============ */}
        <div className="flex min-w-0 flex-col gap-5 xl:col-span-8">
          {/* 空态引导 */}
          {!hasResult && !running && (
            <Card
              icon={<Bot size={16} />}
              title="营销内容工作台"
              subtitle="先在左侧填写生成需求，一键启动多 Agent 协同"
              bodyClassName="flex items-center justify-center p-8"
            >
              <div className="max-w-md py-6 text-center">
                <div className="mx-auto mb-4 grid h-14 w-14 place-items-center rounded-2xl bg-brand-50 text-brand-600">
                  <Wand2 size={26} />
                </div>
                <h4 className="text-[15px] font-semibold text-slate-700">从灵感初稿到合规可发布</h4>
                <p className="mt-2 text-xs leading-6 text-slate-400">
                  TrendAgent 捕捉乡土文化切入角度 → CopywriterAgent
                  按朋友圈/小红书/直播口播调性生成文案矩阵 → ComplianceAgent
                  做广告法质检并自动脱敏，最后由运营负责人审批后同步至私域。
                </p>
                <div className="mt-4 flex justify-center gap-2">
                  <Badge tone="green">热点捕捉</Badge>
                  <Badge tone="blue">文案生成</Badge>
                  <Badge tone="amber">合规质检</Badge>
                  <Badge tone="slate">人工审批</Badge>
                </div>
              </div>
            </Card>
          )}

          {/* 链路（运行中骨架 / 结果） */}
          {(running || hasResult) && (
            <Card
              icon={<Sparkles size={16} />}
              title="多 Agent 协同链路"
              subtitle="TrendAgent → CopywriterAgent → ComplianceAgent → 人工复核（先审后发）"
              actions={
                running ? (
                  <Badge tone="amber" dot>
                    生成中
                  </Badge>
                ) : (
                  <Badge tone={approved ? 'green' : 'amber'} dot>
                    {approved ? '已审批' : '待人工复核'}
                  </Badge>
                )
              }
            >
              <AgentChain
                nodes={displayNodes}
                visibleCount={displayVisible}
                running={running}
                humanApproved={approved}
              />
            </Card>
          )}

          {/* 结果：三端文案 / 合规报告 / 审批 */}
          {hasResult && result && compliance && (
            <>
              <Card
                icon={<ShieldCheck size={16} />}
                title="分渠道文案 · 三端适配"
                subtitle={`共 ${result.copies.length} 套 · 均通过 ComplianceAgent 自动脱敏`}
              >
                <CopyPreview copies={result.copies} riskTerms={compliance.risk_terms_detected} />
              </Card>

              <Card
                icon={<ShieldCheck size={16} />}
                title="广告法合规质检报告"
                subtitle={`ComplianceAgent 输出 · 评分 ${compliance.score}/100`}
              >
                <CompliancePanel
                  compliance={compliance}
                  sourceName={productName.trim()}
                  sourcePoints={sellingPoints}
                />
              </Card>

              {/* 人工审批 */}
              <Card
                icon={<Bot size={16} />}
                title="人工复核 · 先审后发"
                subtitle="AI 仅产出待复核初稿，发布决策权保留给运营负责人"
              >
                <div className="flex flex-col gap-4">
                  {approved ? (
                    <div className="flex items-start gap-3 rounded-xl border border-brand-200 bg-brand-50 p-4">
                      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-brand-600 text-white">
                        <BadgeCheck size={18} />
                      </span>
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-brand-800">
                          审批通过，已同步至私域运营工作台
                        </p>
                        <p className="mt-1 text-xs leading-5 text-brand-700/80">
                          {result.copies.map((c) => CHANNEL_LABELS[c.channel]).join('、')} ·
                          共 {result.copies.length} 套初稿已推流（模拟推送）
                        </p>
                      </div>
                    </div>
                  ) : (
                    <>
                      <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
                        <p className="flex items-center gap-1.5 text-xs font-semibold text-amber-700">
                          <Info size={13} />
                          当前状态：{result.review_status}（AI 初稿就绪，待人工审批）
                        </p>
                        <p className="mt-1 text-[11px] leading-5 text-amber-700/70">
                          请审阅上方三端文案与合规报告后决定：通过则一键推送至私域发布；驳回则携带问题重新生成。
                        </p>
                      </div>

                      {!compliance.passed && (
                        <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600">
                          <Info size={13} className="shrink-0" />
                          合规检测未通过：服务端已自动脱敏，发布前请务必确认已按整改建议完成人工改写。
                        </div>
                      )}

                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          onClick={approve}
                          className="inline-flex h-9 items-center gap-2 rounded-xl bg-brand-600 px-4 text-[13px] font-semibold text-white shadow-sm transition hover:bg-brand-700"
                        >
                          <Send size={14} />
                          审批通过并同步至私域
                        </button>
                        <button
                          type="button"
                          onClick={rejectRegenerate}
                          disabled={running}
                          className="inline-flex h-9 items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 text-[13px] font-medium text-slate-600 transition hover:border-red-200 hover:bg-red-50 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          <RefreshCw size={14} />
                          驳回并重新生成
                        </button>
                      </div>
                    </>
                  )}
                </div>
              </Card>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
