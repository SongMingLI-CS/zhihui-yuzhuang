"""健康检查路由：``GET /ai/v1/healthz``（探针内对 PostgreSQL 执行一次真实 ping）。"""

from __future__ import annotations

import logging
import time
from typing import Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app import __version__
from app.auth import KNOWLEDGE_READ_ROLES, AuthError, error_response, resolve_principal
from app.capabilities import capabilities_payload
from app.config import get_settings
from app.db.session import connection, ping
from app.schemas.base import ApiResponse, new_request_id, utc_now_ms

logger = logging.getLogger(__name__)
settings = get_settings()

router = APIRouter(tags=["health"])


class HealthData(BaseModel):
    """健康检查载荷。"""

    service: str = Field(..., description="服务名")
    status: str = Field(default="ok", description="整体健康状态")
    database: str = Field(..., description="数据库连通状态：connected / unavailable")
    latencyMs: Optional[int] = Field(default=None, description="本次数据库 ping 耗时（毫秒）")
    version: str = Field(..., description="服务版本")


@router.get("/healthz", summary="健康检查（含 PostgreSQL ping）")
async def healthz(request: Request) -> JSONResponse:
    """探针：校验一次 Postgres ping，连通返回 HTTP 200 且 database=connected。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    started = time.perf_counter()
    try:
        await ping()
    except Exception as exc:  # noqa: BLE001 - 探针需要捕获一切数据库异常
        latency_ms = int((time.perf_counter() - started) * 1000)
        logger.warning("healthz 数据库 ping 失败: %s", exc)
        payload = ApiResponse.fail(
            code="A5000",
            message=f"database unavailable: {exc}",
            request_id=request_id,
        )
        return JSONResponse(status_code=503, content=payload.model_dump(mode="json"))

    latency_ms = int((time.perf_counter() - started) * 1000)
    data = HealthData(
        service=settings.app_name,
        status="ok",
        database="connected",
        latencyMs=latency_ms,
        version=__version__,
    )
    payload = ApiResponse.ok(data=data, request_id=request_id, message="数据库连通正常")
    return JSONResponse(status_code=200, content=payload.model_dump(mode="json"))


# ============================================================
# 阶段 F：liveness / readiness / capabilities 分离
# ============================================================


class ReadinessChecks(BaseModel):
    """readiness 各项检查结果（不含任何密钥）。"""

    database: str = Field(description="connected / unavailable")
    embedding: str = Field(description="REAL / MOCK / UNAVAILABLE")
    llm: str = Field(description="REAL / MOCK / UNAVAILABLE")
    knowledgeDocs: Optional[int] = Field(default=None, description="知识库文档数（不可查时为 null）")
    knowledgeEmpty: Optional[bool] = Field(default=None, description="知识库是否为空（影响问答可用性）")
    appEnv: str = Field(description="运行环境")
    demoMode: bool = Field(description="演示开关")


@router.get("/livez", summary="存活探针（进程是否运行，不依赖外部依赖）")
async def livez() -> JSONResponse:
    """liveness：进程存活即 200，用于容器 restart 判定。"""
    payload = ApiResponse.ok(
        data={"status": "alive", "service": settings.app_name, "version": __version__},
        message="进程存活",
    )
    return JSONResponse(status_code=200, content=payload.model_dump(mode="json"))


@router.get("/readyz", summary="就绪探针（数据库 + AI 真实能力 + 知识库基础状态）")
async def readyz(request: Request) -> JSONResponse:
    """readiness：数据库连通且 AI 真实能力可用才 200；否则 503 并给出原因（不泄露密钥）。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    reasons: list[str] = []

    # 1) 数据库
    database = "connected"
    try:
        await ping()
    except Exception as exc:  # noqa: BLE001
        database = "unavailable"
        reasons.append(f"database unavailable: {exc}")

    # 2) AI 能力（真实/降级/不可用）
    payload_caps = capabilities_payload(settings)
    embedding_mode = str(payload_caps["embedding"]["mode"])
    llm_mode = str(payload_caps["llm"]["mode"])
    if embedding_mode == "UNAVAILABLE":
        reasons.append(payload_caps["embedding"]["detail"])
    if llm_mode == "UNAVAILABLE":
        reasons.append(payload_caps["llm"]["detail"])

    # 3) 知识库基础状态（尽力而为；表不存在等异常仅记录）
    knowledge_docs: Optional[int] = None
    knowledge_empty: Optional[bool] = None
    if database == "connected":
        try:
            from app.db.init_tables import KNOWLEDGE_CHUNK_TABLE  # noqa: PLC0415
            async with connection() as conn:  # noqa: PLC0415
                async with conn.cursor() as cur:
                    await cur.execute(
                        f"SELECT COUNT(DISTINCT doc_title)::int AS docs FROM {KNOWLEDGE_CHUNK_TABLE}"
                    )
                    row = await cur.fetchone()
                    knowledge_docs = int(row["docs"]) if row else 0
                    knowledge_empty = knowledge_docs == 0
        except Exception as exc:  # noqa: BLE001
            logger.warning("readyz 知识库状态查询失败: %s", exc)

    checks = ReadinessChecks(
        database=database,
        embedding=embedding_mode,
        llm=llm_mode,
        knowledgeDocs=knowledge_docs,
        knowledgeEmpty=knowledge_empty,
        appEnv=settings.app_env,
        demoMode=settings.demo_mode,
    )
    ready = database == "connected" and not reasons
    if not ready:
        content = {
            "code": "A5004",
            "message": "；".join(reasons) or "readiness 未通过",
            "data": checks.model_dump(mode="json"),
            "timestamp": utc_now_ms(),
            "requestId": request_id,
        }
        return JSONResponse(status_code=503, content=content)

    ok_payload = ApiResponse.ok(data=checks, request_id=request_id, message="AI 服务就绪")
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))


@router.get("/capabilities", summary="AI 能力状态（需登录；不含密钥）")
async def capabilities(request: Request) -> JSONResponse:
    """返回 LLM / Embedding 是否已配置、当前模式与模型名，便于运维确认自配置结果。"""
    request_id = getattr(request.state, "request_id", None) or new_request_id()
    try:
        resolve_principal(request, KNOWLEDGE_READ_ROLES)
    except AuthError as exc:
        return error_response(exc, request_id)
    ok_payload = ApiResponse.ok(data=capabilities_payload(settings), request_id=request_id)
    return JSONResponse(status_code=200, content=ok_payload.model_dump(mode="json"))
