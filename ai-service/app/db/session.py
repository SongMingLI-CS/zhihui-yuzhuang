"""PostgreSQL / pgvector 连接池与连接生命周期管理。

- ``pool``       异步连接池（应用运行时主用，psycopg_pool.AsyncConnectionPool）
- ``sync_pool``  同步连接池（运维脚本 / 一次性管理任务复用，psycopg_pool.ConnectionPool）

vector 扩展的创建与类型适配器注册分开处理：

1. ``CREATE EXTENSION IF NOT EXISTS vector`` —— 只在 ``open_pool()`` /
   ``open_sync_pool()`` 打开连接池**之前**，用一条独立的 autocommit 连接执行
   一次。绝不能放进 psycopg_pool 的 ``configure`` 回调：当 ``min_size >= 2`` 时，
   多个新建连接会**并发**执行该语句，PostgreSQL 可能报
   ``duplicate key value violates unique constraint "pg_extension_name_index"``。

2. pgvector 类型适配器注册 —— 保留在 ``configure`` 回调中（``register_vector`` /
   ``register_vector_async``），它只做 ``TypeInfo.fetch`` 之类的只读查询，
   可安全并发执行。

注意：psycopg_pool 要求 ``configure`` 回调返回后连接处于 IDLE 事务状态，
因此注册完成后需显式 ``commit()``。

使用约束：pgvector 的 psycopg 适配器**只**为 ``pgvector.Vector`` 与
``numpy.ndarray`` 注册 dump 规则；传入普通 Python ``list``（如 ``[0.1, 0.2]``）
不会被编码为 ``vector``，而是被 psycopg 编码成数组（``smallint[]`` / ``float8[]``），
向 ``vector`` 列写入会触发 ``DatatypeMismatch``。业务侧写入 / 检索向量时请显式使用
``Vector([...])``（本模块已 re-export ``Vector``）：
    INSERT INTO ... (embedding) VALUES (%s)   -- 参数传 Vector([0.1, 0.2, ...])
    SELECT ... ORDER BY embedding <=> %s      -- 参数同样传 Vector([...])
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import pgvector.psycopg
import psycopg
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool, ConnectionPool
from pgvector import Vector  # re-export：业务侧统一用 Vector([...]) 编码向量

from app.config import get_settings

logger = logging.getLogger(__name__)

settings = get_settings()

__all__ = [
    "Vector",
    "pool",
    "sync_pool",
    "open_pool",
    "close_pool",
    "open_sync_pool",
    "close_sync_pool",
    "connection",
    "ping",
]

# ---------------------------------------------------------------- vector 扩展（一次性创建）


async def _ensure_pgvector_extension() -> None:
    """用一条独立 autocommit 连接确保 vector 扩展已创建（幂等，立即可见）。

    放在 pool.open() 之前执行，避免多连接并发 ``CREATE EXTENSION`` 触发唯一约束冲突。
    """
    conn = await psycopg.AsyncConnection.connect(
        conninfo=settings.database_url, autocommit=True
    )
    try:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
    finally:
        await conn.close()


def _ensure_pgvector_extension_sync() -> None:
    """同步版本的一次性 vector 扩展创建。"""
    conn = psycopg.connect(settings.database_url, autocommit=True)
    try:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
    finally:
        conn.close()


# ---------------------------------------------------------------- 连接级 configure（只注册类型）


async def _init_async_conn(conn: psycopg.AsyncConnection[Any]) -> None:
    """异步连接 configure：注册 pgvector 类型适配器（只读查询，可安全并发）。"""
    await pgvector.psycopg.register_vector_async(conn)
    await conn.commit()


def _init_sync_conn(conn: psycopg.Connection[Any]) -> None:
    """同步连接 configure：注册 pgvector 类型适配器。"""
    pgvector.psycopg.register_vector(conn)
    conn.commit()


# ---------------------------------------------------------------- 连接池定义

pool: AsyncConnectionPool = AsyncConnectionPool(
    conninfo=settings.database_url,
    min_size=settings.db_pool_min_size,
    max_size=settings.db_pool_max_size,
    kwargs={"row_factory": dict_row},
    configure=_init_async_conn,
    open=False,
    name="yuzhuang-ai-async-pool",
)

sync_pool: ConnectionPool = ConnectionPool(
    conninfo=settings.database_url,
    min_size=settings.db_pool_min_size,
    max_size=settings.db_pool_max_size,
    kwargs={"row_factory": dict_row},
    configure=_init_sync_conn,
    open=False,
    name="yuzhuang-ai-sync-pool",
)

_async_opened = False
_sync_opened = False


# ---------------------------------------------------------------- 生命周期管理


async def open_pool(timeout: float | None = None) -> None:
    """启动异步连接池并等待最小连接就绪；DB 不可达时抛出异常。

    打开连接池前先用独立连接创建 vector 扩展（幂等），避免连接池
    ``min_size>=2`` 的多连接并发 CREATE 冲突。
    """
    global _async_opened
    if _async_opened:
        return
    await _ensure_pgvector_extension()
    await pool.open(wait=True, timeout=timeout or settings.db_connect_timeout)
    _async_opened = True
    logger.info(
        "异步 PostgreSQL/pgvector 连接池已就绪 min=%s max=%s",
        settings.db_pool_min_size,
        settings.db_pool_max_size,
    )


async def close_pool() -> None:
    """关闭异步连接池。"""
    global _async_opened
    if _async_opened:
        await pool.close()
        _async_opened = False
        logger.info("异步连接池已关闭")


def open_sync_pool(timeout: float | None = None) -> None:
    """启动同步连接池（供运维脚本使用）。"""
    global _sync_opened
    if _sync_opened:
        return
    _ensure_pgvector_extension_sync()
    sync_pool.open(wait=True, timeout=timeout or settings.db_connect_timeout)
    _sync_opened = True
    logger.info("同步 PostgreSQL/pgvector 连接池已就绪")


def close_sync_pool() -> None:
    """关闭同步连接池。"""
    global _sync_opened
    if _sync_opened:
        sync_pool.close()
        _sync_opened = False
        logger.info("同步连接池已关闭")


# ---------------------------------------------------------------- 查询助手


@asynccontextmanager
async def connection(timeout: float | None = None) -> AsyncIterator[psycopg.AsyncConnection[Any]]:
    """从异步连接池借出一条连接（用完自动归还 / 回滚）。"""
    async with pool.connection(timeout=timeout or settings.db_connect_timeout) as conn:
        yield conn


async def ping(timeout: float | None = None) -> None:
    """对数据库执行一次真实 ping（``SELECT 1``）；失败抛异常由调用方判定健康。"""
    async with connection(timeout) as conn:
        await conn.execute("SELECT 1")
