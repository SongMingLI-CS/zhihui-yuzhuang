"""特产营销多 Agent 协同生成与合规质检数据契约。

字段命名 / 语义与 ``docs/api-spec.yaml`` 统一响应包裹约定保持一致：
- 外层一律使用 :class:`app.schemas.base.ApiResponse`（``code="00000"`` 表示成功）；
- 路由从请求头 ``X-Tenant-Id`` 读取租户标识；
- ``review_status`` 固定为 ``PENDING_HUMAN_REVIEW``，契合 B 端“AI 初稿 +
  人工复核”的人机协同理念（合规脱敏后的文案仍需运营人员确认后发布）。

渠道枚举（与前端渠道矩阵对齐）：
- ``MOMENTS``    微信朋友圈
- ``RED_BOOK``   小红书
- ``LIVESTREAM`` 直播口播
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

# 目标营销渠道（对齐前端渠道矩阵）
MarketingChannel = Literal["MOMENTS", "RED_BOOK", "LIVESTREAM"]

# 默认渠道矩阵：朋友圈 / 小红书 / 直播
DEFAULT_CHANNELS: list[MarketingChannel] = ["MOMENTS", "RED_BOOK", "LIVESTREAM"]

# 人机协同审核态（固定值，契合 B 端先审后发理念）
REVIEW_STATUS_PENDING_HUMAN_REVIEW = "PENDING_HUMAN_REVIEW"

# 渠道中文标签（用于 Agent 思考摘要与日志）
CHANNEL_LABELS: dict[str, str] = {
    "MOMENTS": "微信朋友圈",
    "RED_BOOK": "小红书",
    "LIVESTREAM": "直播口播",
}


class MarketingGenerateRequest(BaseModel):
    """特产营销生成请求体（POST /ai/v1/marketing/generate）。"""

    product_name: str = Field(
        ...,
        min_length=2,
        max_length=50,
        description="特产名称（2-50 字）",
        examples=["于庄传统石磨小磨香油"],
    )
    selling_points: list[str] = Field(
        default_factory=list,
        max_length=10,
        description="核心卖点（可为空，为空时由编排层补默认卖点）",
        examples=[["古法初榨", "无任何添加剂", "芝麻原香浓郁"]],
    )
    target_audience: Optional[str] = Field(
        default=None,
        max_length=100,
        description="目标客群（可为空，如「注重食品健康的家庭主妇」）",
        examples=["注重食品健康的家庭主妇"],
    )
    channel_preferences: list[MarketingChannel] = Field(
        default_factory=lambda: list(DEFAULT_CHANNELS),
        min_length=0,
        max_length=5,
        description="目标渠道（缺省为朋友圈/小红书/直播全渠道）",
        examples=[["MOMENTS", "RED_BOOK", "LIVESTREAM"]],
    )

    @field_validator("product_name")
    @classmethod
    def _clean_product_name(cls, value: str) -> str:
        """去除首尾空白；空串直接由 min_length 拦下（422）。"""
        return (value or "").strip()

    @field_validator("selling_points")
    @classmethod
    def _clean_selling_points(cls, value: list[str]) -> list[str]:
        """清洗卖点：去空白、去空项、单条限长 60 字、去重保序。"""
        seen: set[str] = set()
        cleaned: list[str] = []
        for point in value or []:
            text = (point or "").strip()
            if not text:
                continue
            text = text[:60]
            if text in seen:
                continue
            seen.add(text)
            cleaned.append(text)
        return cleaned

    @field_validator("target_audience")
    @classmethod
    def _clean_target_audience(cls, value: Optional[str]) -> Optional[str]:
        """去首尾空白；空串归一化为 None。"""
        text = (value or "").strip()
        return text if text else None

    @field_validator("channel_preferences")
    @classmethod
    def _dedupe_channels(cls, value: list[MarketingChannel]) -> list[MarketingChannel]:
        """渠道去重保序；为空列表视为「未指定」，由编排层回退全渠道。"""
        seen: set[str] = set()
        cleaned: list[MarketingChannel] = []
        for channel in value or []:
            if channel in seen:
                continue
            seen.add(channel)
            cleaned.append(channel)
        return cleaned


class AgentThoughtNode(BaseModel):
    """协同链路上的单个 Agent 思考节点（透明化多 Agent 分工）。"""

    agent_name: str = Field(..., description="Agent 名称（TrendAgent / CopywriterAgent / ComplianceAgent）")
    output_summary: str = Field(..., description="该 Agent 本轮的输出摘要")


class MarketingCopyItem(BaseModel):
    """单渠道营销文案条目。"""

    channel: MarketingChannel = Field(..., description="渠道：MOMENTS / RED_BOOK / LIVESTREAM")
    title: str = Field(..., description="标题/首行钩子")
    content: str = Field(..., description="正文（按渠道调性差异化生成）")
    call_to_action: str = Field(..., description="转化引导 CTA")


class ComplianceReport(BaseModel):
    """广告法合规质检报告（ComplianceAgent 输出）。"""

    score: int = Field(..., ge=0, le=100, description="合规评分 0-100（100=完全合规）")
    passed: bool = Field(..., description="是否通过质检（存在任何风险词即不通过）")
    risk_terms_detected: list[str] = Field(
        default_factory=list,
        description="命中的敏感/违禁词（如「最强」「顶级」「包治百病」）",
    )
    revision_suggestions: list[str] = Field(
        default_factory=list,
        description="整改建议（含《广告法》条款依据与脱敏替换提示）",
    )


class MarketingGenerateResponse(BaseModel):
    """营销生成响应载荷（data 字段内容）。"""

    taskId: Optional[int] = Field(
        default=None, description="持久化任务 ID（用于审批/发布留痕；未落库时为 null）"
    )
    thought_chain: list[AgentThoughtNode] = Field(
        default_factory=list,
        description="Agent 协同思考路径（Trend→Copywriter→Compliance）",
    )
    copies: list[MarketingCopyItem] = Field(
        default_factory=list,
        description="各渠道差异化文案（已过合规脱敏）",
    )
    compliance: ComplianceReport = Field(..., description="广告法合规质检报告")
    review_status: str = Field(
        default=REVIEW_STATUS_PENDING_HUMAN_REVIEW,
        description="审核状态（固定为 PENDING_HUMAN_REVIEW，先审后发）",
    )


class MarketingTaskItem(BaseModel):
    """营销任务台账条目（持久化审批留痕）。"""

    id: int
    tenantId: str
    productName: str
    complianceScore: int
    compliancePassed: bool
    reviewStatus: str = Field(description="PENDING_HUMAN_REVIEW / APPROVED / REJECTED")
    createdBy: str = ""
    createdAt: str = ""
    reviewedBy: str = ""
    reviewedAt: str = ""
    reviewComment: str = ""
    publishedAt: str = ""


class MarketingTaskActionRequest(BaseModel):
    """审批/发布动作请求体。"""

    comment: str = Field(default="", max_length=500, description="审批意见（可选）")


class MarketingTaskPage(BaseModel):
    """营销任务分页结果。"""

    items: list[MarketingTaskItem] = Field(default_factory=list)
    page: int
    pageSize: int
    total: int
    totalPages: int


__all__ = [
    "MarketingChannel",
    "DEFAULT_CHANNELS",
    "REVIEW_STATUS_PENDING_HUMAN_REVIEW",
    "CHANNEL_LABELS",
    "MarketingGenerateRequest",
    "AgentThoughtNode",
    "MarketingCopyItem",
    "ComplianceReport",
    "MarketingGenerateResponse",
    "MarketingTaskItem",
    "MarketingTaskActionRequest",
    "MarketingTaskPage",
]
