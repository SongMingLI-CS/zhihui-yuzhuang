"""特产营销多 Agent 协同生成与合规质检编排服务。

流水线（对齐 docs 契约 ``POST /ai/v1/marketing/generate``）：

.. code-block:: text

   MarketingGenerateRequest
        │  X-Tenant-Id（租户隔离/日志）
        ▼
   ┌─────────────────┐
   │  TrendAgent      │  结合豫东民俗 / 老子文化 / 节气，提炼 2~3 个乡土切入点
   └────────┬────────┘
            ▼
   ┌─────────────────┐
   │  CopywriterAgent │  朋友圈 / 小红书 / 直播 → 差异化文案矩阵
   └────────┬────────┘
            ▼
   ┌──────────────────┐
   │  ComplianceAgent  │  广告法二次质检 → 合规评分 + 必要时脱敏
   └────────┬─────────┘
            ▼
   MarketingGenerateResponse
        （thought_chain + copies + compliance + review_status=PENDING_HUMAN_REVIEW）

运行韧性（与 RAG 问答同源理念）：
- 复用 ``app.config.get_settings()``（``deepseek_api_key`` / ``base_url``）与
  ``app.llm.deepseek.DeepSeekChatClient`` 单例及其异常分型；
- 未配置真实 API Key（或 ``force_offline=True``）时，编排器自动优雅回退到确定性
  Mock 模板链路（高质量预置文案 + 规则合规），接口绝不崩毁、离线联调畅通；
- 配置密钥时各 Agent 优先调用 DeepSeek 生成富文本，任何异常/解析失败都安全降级
  回模板链路（见 :class:`app.agents.marketing_agents._AgentBase`）。
"""

from __future__ import annotations

import logging
from typing import Optional

from app.agents.marketing_agents import (
    ComplianceAgent,
    CopywriterAgent,
    TrendAgent,
    compliance_summary,
)
from app.config import Settings, get_settings
from app.llm.deepseek import DeepSeekChatClient, build_deepseek_chat
from app.schemas.marketing import (
    AgentThoughtNode,
    MarketingGenerateRequest,
    MarketingGenerateResponse,
)

logger = logging.getLogger(__name__)


class MarketingOrchestrator:
    """串联 TrendAgent → CopywriterAgent → ComplianceAgent 的营销生成编排器。"""

    def __init__(
        self,
        llm: Optional[DeepSeekChatClient] = None,
        *,
        settings: Optional[Settings] = None,
        force_offline: bool = False,
    ) -> None:
        cfg = settings or get_settings()
        self.settings = cfg
        self.force_offline = bool(force_offline)
        self.llm = llm if llm is not None else build_deepseek_chat()

        # 三个 Agent 共享同一 LLM 客户端与离线开关（复用 DeepSeek 单例）
        self.trend_agent = TrendAgent(self.llm, force_offline=self.force_offline)
        self.copywriter_agent = CopywriterAgent(self.llm, force_offline=self.force_offline)
        self.compliance_agent = ComplianceAgent()

        online = bool(getattr(self.llm, "available", False)) and not self.force_offline
        logger.info(
            "MarketingOrchestrator 就绪（%s）：TrendAgent→CopywriterAgent→ComplianceAgent",
            "DeepSeek 在线" if online else "离线 Mock 模板",
        )

    async def generate(
        self,
        request: MarketingGenerateRequest,
        tenant_id: str = "global",
    ) -> MarketingGenerateResponse:
        """执行多 Agent 协同生成 + 合规质检全链路。"""
        # 1) TrendAgent：乡土文化切入点
        angles, trend_summary = await self.trend_agent.run(request)

        # 2) CopywriterAgent：分渠道差异化文案
        copies, copy_summary = await self.copywriter_agent.run(request, angles)

        # 3) ComplianceAgent：广告法二次质检 + 脱敏替换
        compliance_report, sanitized_copies = self.compliance_agent.run(request, copies)

        thought_chain = [
            AgentThoughtNode(
                agent_name=self.trend_agent.agent_name,
                output_summary=trend_summary,
            ),
            AgentThoughtNode(
                agent_name=self.copywriter_agent.agent_name,
                output_summary=copy_summary,
            ),
            AgentThoughtNode(
                agent_name=self.compliance_agent.agent_name,
                output_summary=compliance_summary(compliance_report),
            ),
        ]

        logger.info(
            "营销生成完成 tenant=%s product=%.40s copies=%d compliance_score=%d passed=%s",
            tenant_id,
            request.product_name,
            len(sanitized_copies),
            compliance_report.score,
            compliance_report.passed,
        )

        return MarketingGenerateResponse(
            thought_chain=thought_chain,
            copies=sanitized_copies,
            compliance=compliance_report,
        )


def build_marketing_orchestrator(**kwargs) -> MarketingOrchestrator:
    """按全局配置构建 :class:`MarketingOrchestrator` 的便捷工厂。"""
    return MarketingOrchestrator(**kwargs)


__all__ = [
    "MarketingOrchestrator",
    "build_marketing_orchestrator",
]
