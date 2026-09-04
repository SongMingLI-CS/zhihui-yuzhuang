"""农技问答路由：``POST /ai/v1/qa/ask``。

- 从请求头 ``X-Tenant-Id`` 读取租户标识（缺省 ``global``）；
- 校验入参 :class:`AgriQARequest`（对齐 docs/api-spec.yaml）；
- 返回统一包裹 :class:`ApiResponse[AgriQAResponse]`；
- 异常处理：编排/检索异常统一捕获并记录，返回 HTTP 500 + 标准错误包裹。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Header, Request
from fastapi.responses import JSONResponse

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
    tenant_id: str = Header(default="global", alias="X-Tenant-Id"),
) -> JSONResponse:
    """接收农户问题，执行 RAG 检索 + DeepSeek 生成，返回带溯源的严谨回答。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    effective_tenant = (tenant_id or "global").strip() or "global"
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
