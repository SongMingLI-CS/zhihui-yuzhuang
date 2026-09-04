"""接口数据模型（对齐 docs/api-spec.yaml 契约）。"""

from app.schemas.base import ApiResponse
from app.schemas.marketing import (
    AgentThoughtNode,
    ComplianceReport,
    MarketingCopyItem,
    MarketingGenerateRequest,
    MarketingGenerateResponse,
)
from app.schemas.qa import AgriQARequest, AgriQAResponse, Citation

__all__ = [
    "ApiResponse",
    "AgriQARequest",
    "AgriQAResponse",
    "Citation",
    "AgentThoughtNode",
    "MarketingGenerateRequest",
    "MarketingCopyItem",
    "ComplianceReport",
    "MarketingGenerateResponse",
]
