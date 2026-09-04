"""健康检查路由：``GET /ai/v1/healthz``（探针内对 PostgreSQL 执行一次真实 ping）。"""

from __future__ import annotations

import logging
import time
from typing import Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app import __version__
from app.config import get_settings
from app.db.session import ping
from app.schemas.base import ApiResponse, new_request_id

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
