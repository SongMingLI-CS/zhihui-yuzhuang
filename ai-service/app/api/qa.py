"""农技问答路由：``POST /ai/v1/qa/ask``。

- 匿名允许（公共知识域，审计 P0-1「匿名 QA 若保留，只能访问公共知识域并限流」）；
- 已认证请求按令牌 tenantId 检索（可访问本租户 ∪ global 知识）；
- 校验入参 :class:`AgriQARequest`（对齐 docs/api-spec.yaml）；
- 返回统一包裹 :class:`ApiResponse[AgriQAResponse]`；
- 异常处理：编排/检索异常统一捕获并记录，返回 HTTP 500 + 标准错误包裹。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.auth import PUBLIC_TENANT_ID, optional_principal
from app.ratelimit import anonymous_qa_limiter
from app.schemas.base import ApiResponse, new_request_id
from app.schemas.qa import AgriQARequest, AgriQAResponse
from app.services.qa_service import AgriQAService

logger = logging.getLogger(__name__)

router = APIRouter(tags=["qa"])

# 惰性单例：构建 Retriever/Embedder/LLM 客户端均不发起网络请求，
# 首次调用时按全局配置（含 Mock 判定）初始化一次即可。
_service: "AgriQAService | None" = None


def _get_service() -> AgriQAService:
    """按需构建 / 复用 :class:`AgriQAService` 单例。"""
    global _service
    if _service is None:
        _service = AgriQAService()
    return _service


@router.post(
    "/qa/ask",
    summary="农技与惠农政策检索增强问答 (RAG)",
    response_model=ApiResponse[AgriQAResponse],
    responses={
        500: {"description": "问答编排/检索异常（统一错误包裹）"},
    },
)
async def qa_ask(
    payload: AgriQARequest,
    request: Request,
) -> JSONResponse:
    """接收农户问题，执行 RAG 检索 + DeepSeek 生成，返回带溯源的严谨回答。

    匿名调用仅可访问公共知识域（``global``）并按 IP 限流；已认证按令牌租户检索。
    """
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    principal = optional_principal(request)
    if principal is None:
        client_ip = request.client.host if request.client else "unknown"
        allowed, _remaining = anonymous_qa_limiter.allow(f"qa:{client_ip}")
        if not allowed:
            error_payload = ApiResponse.fail(
                code="A1003", message="匿名问答请求过于频繁，请稍后再试", request_id=request_id
            )
            return JSONResponse(status_code=429, content=error_payload.model_dump(mode="json"))
        effective_tenant = PUBLIC_TENANT_ID
    else:
        effective_tenant = principal.tenant_id or PUBLIC_TENANT_ID
    try:
        result = await _get_service().answer_question(
            payload, tenant_id=effective_tenant
        )
    except Exception as exc:  # noqa: BLE001 - 统一异常处理，返回标准错误包裹
        logger.exception(
            "农技问答处理失败 tenant=%s question=%.80s",
            effective_tenant,
            payload.question,
        )
        error_payload = ApiResponse.fail(
            code="A5001",
            message=f"农技问答服务暂时不可用：{exc}",
            request_id=request_id,
        )
        return JSONResponse(
            status_code=500, content=error_payload.model_dump(mode="json")
        )

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


__all__ = ["router"]
