"""知识库管理路由：``/ai/v1/knowledge/docs``。

- ``POST /knowledge/docs/upload`` —— 上传 .txt/.md 文本并切片向量化入库（multipart）；
- ``GET  /knowledge/docs``     —— 列出本租户 ∪ global 文档；
- ``POST /knowledge/docs/delete`` —— 删除指定租户文档（幂等）。

错误包裹约定：参数/业务错误 HTTP 400 + ``A1001``；系统异常 HTTP 500 + ``A5001``。
注意：AI 侧当前无身份校验（整体列入「AI 端点治理」后续阶段），请勿在生产暴露。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, File, Form, Header, Request, UploadFile
from fastapi.responses import JSONResponse

from app.schemas.base import ApiResponse, new_request_id
from app.schemas.knowledge import (
    KnowledgeDeleteRequest,
    KnowledgeDeleteResponse,
    KnowledgeDocItem,
    KnowledgeUploadResponse,
)
from app.services.knowledge_service import KnowledgeService, KnowledgeServiceError

logger = logging.getLogger(__name__)

router = APIRouter(tags=["knowledge"])

_service: "KnowledgeService | None" = None


def _get_service() -> KnowledgeService:
    """按需构建 / 复用知识库服务单例（Splitter/Embedder 构建不发网络请求）。"""
    global _service
    if _service is None:
        _service = KnowledgeService()
    return _service


@router.post(
    "/knowledge/docs/upload",
    summary="上传知识文档并入库（.txt/.md → 切片 → 向量化）",
    response_model=ApiResponse[KnowledgeUploadResponse],
)
async def upload_doc(
    request: Request,
    file: UploadFile = File(..., description="知识文档（.txt / .md）"),
    tenant_id: str = Form(default="global", description="归属租户"),
    category: str = Form(default="GENERAL", description="知识分类，如 DISEASE_PEST"),
) -> JSONResponse:
    """接收上传文件，同步完成切分与入库；失败返回 400/500 标准包裹。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    effective_tenant = (tenant_id or "global").strip() or "global"
    try:
        raw = await file.read()
        text = raw.decode("utf-8", errors="replace")
        result = await _get_service().upload_text(
            tenant_id=effective_tenant,
            filename=file.filename or "unnamed.txt",
            text=text,
            category=category or "GENERAL",
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
async def list_docs(
    request: Request,
    tenant_id: str = Header(default="global", alias="X-Tenant-Id"),
) -> JSONResponse:
    """返回文档元信息列表（切片数由 chunk 表聚合）。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    effective_tenant = (tenant_id or "global").strip() or "global"
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
async def delete_doc(
    payload: KnowledgeDeleteRequest,
    request: Request,
) -> JSONResponse:
    """按 (tenantId, title) 删除该文档全部切片行，返回删除行数。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        result = await _get_service().delete_doc(
            tenant_id=payload.tenantId.strip(), title=payload.title.strip()
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("knowledge delete failed tenant=%s title=%s",
                         payload.tenantId, payload.title)
        error_payload = ApiResponse.fail(
            code="A5001", message=f"知识库服务暂时不可用：{exc}", request_id=request_id
        )
        return JSONResponse(status_code=500, content=error_payload.model_dump(mode="json"))

    ok_payload = ApiResponse.ok(data=result, request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


__all__ = ["router"]
