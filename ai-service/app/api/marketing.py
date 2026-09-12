"""特产营销路由：``POST /ai/v1/marketing/generate``。

- 需有效 JWT，角色限 ``COOPERATIVE/VILLAGE/PLATFORM_ADMIN``（审计 P0-1：营销属于商家运营能力）；
- 租户从已验证身份推导，不信任 ``X-Tenant-Id`` 头；
- 编排 :class:`MarketingOrchestrator`（TrendAgent→CopywriterAgent→ComplianceAgent）；
- 返回统一包裹 :class:`ApiResponse[MarketingGenerateResponse]`；
- 未配置 DeepSeek 密钥且 ``DEMO_MODE=true`` 时走离线 Mock 模板（非演示环境明确降级标识）。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.auth import (
    KNOWLEDGE_WRITE_ROLES,
    AuthError,
    error_response,
    resolve_principal,
)
from app.schemas.base import ApiResponse, new_request_id
from app.schemas.marketing import (
    MarketingGenerateRequest,
    MarketingGenerateResponse,
    MarketingTaskActionRequest,
    MarketingTaskItem,
    MarketingTaskPage,
)
from app.services.marketing_review import ACTION_APPROVE, ACTION_PUBLISH, ACTION_REJECT
from app.services.marketing_service import MarketingOrchestrator, MarketingUnavailableError
from app.services.marketing_task_service import MarketingTaskError, MarketingTaskService

logger = logging.getLogger(__name__)

router = APIRouter(tags=["marketing"])

# 审批/发布角色（村委/平台管理员）；商家可生成但不可自审
_REVIEW_ROLES = ("VILLAGE", "PLATFORM_ADMIN")

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
) -> JSONResponse:
    """接收特产信息，多 Agent 生成差异化文案 + 广告法质检，返回待人工复核初稿。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, KNOWLEDGE_WRITE_ROLES)
    except AuthError as exc:
        return error_response(exc, request_id)
    effective_tenant = principal.tenant_id or "global"
    try:
        result = await _get_service().generate(payload, tenant_id=effective_tenant)
    except MarketingUnavailableError as exc:
        # 生产未配置 LLM 密钥：明确返回“不可用”，不返回离线模板伪装结果
        logger.warning("营销生成不可用 tenant=%s: %s", effective_tenant, exc)
        error_payload = ApiResponse.fail(
            code="A5003",
            message=str(exc),
            request_id=request_id,
        )
        return JSONResponse(status_code=503, content=error_payload.model_dump(mode="json"))
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

    # 持久化任务（DB 不可用时降级：仍返回生成结果，但 taskId 为 null 并记录告警）
    try:
        task_id = await MarketingTaskService().create(
            tenant_id=effective_tenant,
            product_name=payload.product_name,
            request_payload=payload.model_dump(mode="json"),
            response_payload=result.model_dump(mode="json"),
            compliance_score=result.compliance.score,
            compliance_passed=result.compliance.passed,
            created_by=principal.username,
        )
        result = result.model_copy(update={"taskId": task_id})
    except Exception as exc:  # noqa: BLE001
        logger.warning("营销任务持久化失败（结果仍返回，taskId=null）：%s", exc)

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.get(
    "/marketing/tasks",
    summary="营销任务台账（分页，可按审批状态过滤）",
    response_model=ApiResponse[MarketingTaskPage],
)
async def list_marketing_tasks(
    request: Request,
    status: str = "",
    page: int = 1,
    page_size: int = 20,
) -> JSONResponse:
    """查询本租户营销任务（含审批状态/审批人/发布时间），供 B 端审批台账。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, KNOWLEDGE_WRITE_ROLES)
    except AuthError as exc:
        return error_response(exc, request_id)
    try:
        page_data = await MarketingTaskService().list_tasks(
            tenant_id=principal.tenant_id or "global",
            status=status or None,
            page=page,
            page_size=page_size,
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("营销任务查询失败 tenant=%s", principal.tenant_id)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"营销任务服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))
    ok_payload = ApiResponse.ok(data=page_data, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


async def _task_action(
    request: Request, task_id: int, action: str, payload: MarketingTaskActionRequest
) -> JSONResponse:
    """审批/发布动作公共实现。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, _REVIEW_ROLES)
    except AuthError as exc:
        return error_response(exc, request_id)
    try:
        item = await MarketingTaskService().act(
            tenant_id=principal.tenant_id or "global",
            task_id=task_id,
            action=action,
            actor=principal.username,
            comment=payload.comment or "",
        )
    except MarketingTaskError as exc:
        error_payload = ApiResponse.fail(code="A1001", message=str(exc), request_id=request_id)
        return JSONResponse(status_code=400, content=error_payload.model_dump(mode="json"))
    except Exception as exc:  # noqa: BLE001
        logger.exception("营销任务动作失败 action=%s id=%s", action, task_id)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"营销任务服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))
    ok_payload = ApiResponse.ok(data=item, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.post(
    "/marketing/tasks/{task_id}/approve",
    summary="审批通过营销任务（村委/平台管理员）",
    response_model=ApiResponse[MarketingTaskItem],
)
async def approve_marketing_task(
    task_id: int, payload: MarketingTaskActionRequest, request: Request
) -> JSONResponse:
    return await _task_action(request, task_id, ACTION_APPROVE, payload)


@router.post(
    "/marketing/tasks/{task_id}/reject",
    summary="驳回营销任务（村委/平台管理员）",
    response_model=ApiResponse[MarketingTaskItem],
)
async def reject_marketing_task(
    task_id: int, payload: MarketingTaskActionRequest, request: Request
) -> JSONResponse:
    return await _task_action(request, task_id, ACTION_REJECT, payload)


@router.post(
    "/marketing/tasks/{task_id}/publish",
    summary="标记任务已发布（仅 APPROVED 任务可用）",
    response_model=ApiResponse[MarketingTaskItem],
)
async def publish_marketing_task(
    task_id: int, payload: MarketingTaskActionRequest, request: Request
) -> JSONResponse:
    return await _task_action(request, task_id, ACTION_PUBLISH, payload)


__all__ = ["router"]
