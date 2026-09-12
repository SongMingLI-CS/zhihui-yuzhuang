"""知识库管理路由：``/ai/v1/knowledge/docs``。

- ``POST /knowledge/docs/upload`` —— 上传 .txt/.md 文本并切片向量化入库（multipart）；
- ``GET  /knowledge/docs``     —— 列出本租户 ∪ global 文档；
- ``POST /knowledge/docs/delete`` —— 删除指定租户文档（幂等）。

安全（审计 P0-1 / 阶段 B）：上传/删除/列表均需有效 JWT，角色限
``COOPERATIVE/VILLAGE/PLATFORM_ADMIN``（读含 ``GOVERNMENT``）；租户一律从已验证身份推导，
不信任表单 ``tenant_id``。

错误包裹约定：参数/业务错误 HTTP 400 + ``A1001``；未认证 401 + ``A1002``；越权 403 + ``A1003``；
系统异常 HTTP 500 + ``A5001``。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse

from app.auth import (
    KNOWLEDGE_READ_ROLES,
    KNOWLEDGE_WRITE_ROLES,
    AuthError,
    Principal,
    error_response,
    resolve_principal,
)
from app.schemas.base import ApiResponse, new_request_id
from app.schemas.knowledge import (
    KnowledgeDeleteRequest,
    KnowledgeDeleteResponse,
    KnowledgeDocItem,
    KnowledgeReviewRequest,
    KnowledgeReviewResponse,
    KnowledgeUploadResponse,
    KnowledgeVersionItem,
)
from app.services.knowledge_service import KnowledgeService, KnowledgeServiceError

logger = logging.getLogger(__name__)

router = APIRouter(tags=["knowledge"])

# 允许写入共享（global）目录的角色
_SHARED_WRITE_ROLES = {"VILLAGE", "PLATFORM_ADMIN"}

# 审核角色（村委/平台管理员；商家可上传但不可自审）
_REVIEW_ROLES = {"VILLAGE", "PLATFORM_ADMIN"}

_service: "KnowledgeService | None" = None


def _get_service() -> KnowledgeService:
    """按需构建 / 复用知识库服务单例（Splitter/Embedder 构建不发网络请求）。"""
    global _service
    if _service is None:
        _service = KnowledgeService()
    return _service


def _resolve_tenant(principal: Principal, requested: str | None) -> str:
    """从已验证身份推导知识归属租户；仅允许受限的 global 共享写入。"""
    if principal.tenant_id == "global":
        return "global"
    requested = (requested or "").strip()
    if requested and requested != principal.tenant_id:
        if requested == "global" and principal.role in _SHARED_WRITE_ROLES:
            return "global"
        raise AuthError(403, "A1003", "无权向其他租户写入知识")
    return principal.tenant_id



@router.post(
    "/knowledge/docs/upload",
    summary="上传知识文档并入库（.txt/.md → 切片 → 向量化）",
    response_model=ApiResponse[KnowledgeUploadResponse],
)
async def upload_doc(
    request: Request,
    file: UploadFile = File(..., description="知识文档（.txt / .md）"),
    tenant_id: str = Form(default="", description="归属租户（受控：仅允许本租户或 global）"),
    category: str = Form(default="GENERAL", description="知识分类，如 DISEASE_PEST"),
) -> JSONResponse:
    """接收上传文件，同步完成切分与入库；失败返回 400/500 标准包裹。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, KNOWLEDGE_WRITE_ROLES)
        effective_tenant = _resolve_tenant(principal, tenant_id)
    except AuthError as exc:
        return error_response(exc, request_id)

    try:
        raw = await file.read()
        result = await _get_service().upload_document(
            tenant_id=effective_tenant,
            filename=file.filename or "unnamed.txt",
            raw=raw,
            category=category or "GENERAL",
            uploaded_by=principal.username,
        )
    except KnowledgeServiceError as exc:
        logger.warning("knowledge upload rejected tenant=%s file=%s: %s",
                       effective_tenant, file.filename, exc)
        error_payload = ApiResponse.fail(code="A1001", message=str(exc), request_id=request_id)
        return JSONResponse(status_code=400, content=error_payload.model_dump(mode="json"))
    except Exception as exc:  # noqa: BLE001 - 统一异常处理
        logger.exception("knowledge upload failed tenant=%s file=%s",
                         effective_tenant, file.filename)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.get(
    "/knowledge/docs",
    summary="列出知识文档（本租户 ∪ global，按入库时间倒序）",
    response_model=ApiResponse[list[KnowledgeDocItem]],
)
async def list_docs(request: Request) -> JSONResponse:
    """返回文档元信息列表（切片数由 chunk 表聚合）；租户取已验证身份。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, KNOWLEDGE_READ_ROLES)
    except AuthError as exc:
        return error_response(exc, request_id)
    effective_tenant = principal.tenant_id or "global"
    try:
        items = await _get_service().list_docs(effective_tenant)
    except Exception as exc:  # noqa: BLE001
        logger.exception("knowledge list failed tenant=%s", effective_tenant)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=items, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.post(
    "/knowledge/docs/delete",
    summary="删除指定租户文档（幂等）",
    response_model=ApiResponse[KnowledgeDeleteResponse],
)
async def delete_doc(payload: KnowledgeDeleteRequest, request: Request) -> JSONResponse:
    """按 (tenantId, title) 删除该文档全部切片行，返回删除行数；租户取已验证身份。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, KNOWLEDGE_WRITE_ROLES)
        effective_tenant = _resolve_tenant(principal, payload.tenantId)
    except AuthError as exc:
        return error_response(exc, request_id)

    try:
        result = await _get_service().delete_doc(
            tenant_id=effective_tenant, title=payload.title.strip()
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("knowledge delete failed tenant=%s title=%s",
                         effective_tenant, payload.title)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.post(
    "/knowledge/docs/review",
    summary="审核知识文档（APPROVED 通过 / REJECTED 驳回；村委/平台管理员）",
    response_model=ApiResponse[KnowledgeReviewResponse],
)
async def review_doc(payload: KnowledgeReviewRequest, request: Request) -> JSONResponse:
    """对已登记文档给出审核结论；审核人取已验证身份，租户取身份或受控 global。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        principal = resolve_principal(request, tuple(_REVIEW_ROLES))
        effective_tenant = _resolve_tenant(principal, payload.tenantId)
    except AuthError as exc:
        return error_response(exc, request_id)

    try:
        result = await _get_service().review_doc(
            tenant_id=effective_tenant,
            title=payload.title.strip(),
            status=payload.status,
            reviewed_by=principal.username,
            comment=payload.comment or "",
        )
    except KnowledgeServiceError as exc:
        error_payload = ApiResponse.fail(code="A1001", message=str(exc), request_id=request_id)
        return JSONResponse(status_code=400, content=error_payload.model_dump(mode="json"))
    except Exception as exc:  # noqa: BLE001
        logger.exception("knowledge review failed tenant=%s title=%s", effective_tenant, payload.title)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.get(
    "/knowledge/docs/versions",
    summary="查询知识文档版本历史（按标题倒序）",
    response_model=ApiResponse[list[KnowledgeVersionItem]],
)
async def list_versions(
    request: Request,
    title: str,
    tenant_id: str = "",
) -> JSONResponse:
    """返回指定文档的版本历史（含内容哈希/大小/上传者/时间）。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    if not title or not title.strip():
        error_payload = ApiResponse.fail(code="A1001", message="title 不能为空", request_id=request_id)
        return JSONResponse(status_code=400, content=error_payload.model_dump(mode="json"))
    try:
        principal = resolve_principal(request, KNOWLEDGE_READ_ROLES)
        effective_tenant = _resolve_tenant(principal, tenant_id)
    except AuthError as exc:
        return error_response(exc, request_id)

    try:
        items = await _get_service().list_versions(effective_tenant, title.strip())
    except Exception as exc:  # noqa: BLE001
        logger.exception("knowledge versions failed tenant=%s title=%s", effective_tenant, title)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=items, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


__all__ = ["router"]

