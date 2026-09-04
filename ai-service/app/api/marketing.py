"""特产营销路由：``POST /ai/v1/marketing/generate``。

- 从请求头 ``X-Tenant-Id`` 读取租户标识（缺省 ``global``）；
- 校验入参 :class:`MarketingGenerateRequest`（对齐数据契约）；
- 编排 :class:`MarketingOrchestrator`（TrendAgent→CopywriterAgent→ComplianceAgent）；
- 返回统一包裹 :class:`ApiResponse[MarketingGenerateResponse]`；
- 异常处理：编排异常统一捕获并记录，返回 HTTP 500 + 标准错误包裹；
  未配置 DeepSeek 密钥时自动走离线 Mock 模板链路，接口不崩毁。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Header, Request
from fastapi.responses import JSONResponse

from app.schemas.base import ApiResponse, new_request_id
from app.schemas.marketing import (
    MarketingGenerateRequest,
    MarketingGenerateResponse,
)
from app.services.marketing_service import MarketingOrchestrator

logger = logging.getLogger(__name__)

router = APIRouter(tags=["marketing"])

# 惰性单例：构建 Orchestrator / LLM 客户端均不发起网络请求，
# 首次调用时按全局配置（含 Mock 判定）初始化一次即可。
_service: "MarketingOrchestrator | None" = None


def _get_service() -> MarketingOrchestrator:
    """按需构建 / 复用 :class:`MarketingOrchestrator` 单例。"""
    global _service
    if _service is None:
        _service = MarketingOrchestrator()
    return _service


@router.post(
    "/marketing/generate",
    summary="特产营销多 Agent 协同生成与广告法合规质检",
    response_model=ApiResponse[MarketingGenerateResponse],
    responses={
        500: {"description": "营销编排异常（统一错误包裹）"},
    },
)
async def marketing_generate(
    payload: MarketingGenerateRequest,
    request: Request,
    tenant_id: str = Header(default="global", alias="X-Tenant-Id"),
) -> JSONResponse:
    """接收特产信息，多 Agent 生成差异化文案 + 广告法质检，返回待人工复核初稿。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    effective_tenant = (tenant_id or "global").strip() or "global"
    try:
        result = await _get_service().generate(payload, tenant_id=effective_tenant)
    except Exception as exc:  # noqa: BLE001 - 统一异常处理，返回标准错误包裹
        logger.exception(
            "特产营销生成失败 tenant=%s product=%.80s",
            effective_tenant,
            payload.product_name,
        )
        error_payload = ApiResponse.fail(
            code="A5002",
            message=f"特产营销生成服务暂时不可用：{exc}",
            request_id=request_id,
        )
        return JSONResponse(
            status_code=500, content=error_payload.model_dump(mode="json")
        )

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


__all__ = ["router"]
