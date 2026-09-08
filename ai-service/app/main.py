"""智汇于庄 · ai-service FastAPI 入口。

挂载 / 装配：
- ``GET /ai/v1/healthz`` —— 健康检查（含 PostgreSQL ping）
- 请求级链路 ID（``X-Request-Id`` / body 内 requestId）
- 启动 / 关闭时管理 PostgreSQL(pgvector) 连接池
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app import __version__
from app.api.health import router as health_router
from app.api.knowledge import router as knowledge_router
from app.api.marketing import router as marketing_router
from app.api.qa import router as qa_router
from app.config import get_settings
from app.db.session import close_pool, open_pool
from app.schemas.base import ApiResponse, new_request_id

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时打开连接池；关闭时优雅释放。"""
    logger.info("ai-service 启动中 env=%s db=%s", settings.app_env, settings.database_url)
    try:
        await open_pool()
    except Exception as exc:  # noqa: BLE001
        # 数据库暂不可达时不阻断进程启动：/healthz 会返回 503，
        # 待数据库恢复后调用方可继续使用连接池。
        logger.error("连接池初始化失败（服务仍可启动，healthz 将报告 503）: %s", exc)
    yield
    await close_pool()
    logger.info("ai-service 已停止")


app = FastAPI(
    title="智汇于庄 · AI 服务",
    description="农技 / 政策 RAG 检索问答 + DeepSeek 多智能体（对接契约 docs/api-spec.yaml）",
    version=__version__,
    lifespan=lifespan,
    docs_url=f"{settings.api_prefix}/docs",
    redoc_url=f"{settings.api_prefix}/redoc",
    openapi_url=f"{settings.api_prefix}/openapi.json",
)


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    """生成 / 透传链路 ID，写入 body 的 requestId 并回写 X-Request-Id 响应头。"""
    request_id = request.headers.get("X-Request-Id") or new_request_id()
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers.setdefault("X-Request-Id", request_id)
    return response


@app.get("/", summary="服务信息", include_in_schema=False)
async def root() -> JSONResponse:
    payload = ApiResponse.ok(
        data={
            "service": settings.app_name,
            "version": __version__,
            "docs": f"{settings.api_prefix}/docs",
            "health": f"{settings.api_prefix}/healthz",
        }
    )
    return JSONResponse(content=payload.model_dump(mode="json"))


# 挂载业务路由（前缀 /ai/v1 与 docs/api-spec.yaml 的 ai servers 对齐）
app.include_router(health_router, prefix=settings.api_prefix)
app.include_router(qa_router, prefix=settings.api_prefix)
app.include_router(knowledge_router, prefix=settings.api_prefix)
app.include_router(marketing_router, prefix=settings.api_prefix)
