"""知识库表结构初始化：``t_knowledge_chunk``。

- 提供同步 / 异步两个实现，均可独立执行（各自建立一条直连连接），
  不依赖 ``app.db.session`` 的连接池是否已打开；
- 幂等：``CREATE TABLE / CREATE INDEX IF NOT EXISTS`` 可安全重复调用；
- 建表前确保 ``vector`` 扩展存在（单连接内执行，避免并发 CREATE 冲突）。

约定：
- ``embedding`` 列维度取 ``settings.embedding_dim``（默认 1536，
  与 EMBEDDING_MODEL=text-embedding-3-small 一致）；
- 为 ``embedding`` 建 HNSW 余弦索引 ``vector_cosine_ops``，支持
  ``<=>`` 相似度检索。当表数据量较小（< ~1 万行）时顺序扫描通常更快，
  索引不影响正确性。
"""

from __future__ import annotations

import psycopg

from app.config import get_settings

settings = get_settings()

__all__ = [
    "KNOWLEDGE_CHUNK_TABLE",
    "EMBEDDING_DIM",
    "CREATE_KNOWLEDGE_CHUNK_TABLE_SQL",
    "CREATE_KNOWLEDGE_CHUNK_INDEX_SQL",
    "init_knowledge_chunk_table",
    "init_knowledge_chunk_table_sync",
]

# 表名（docs/architecture.md 契约）
KNOWLEDGE_CHUNK_TABLE = "t_knowledge_chunk"

# 向量维度：与 config.EMBEDDING_DIM 对齐（vector 列维度不可变更，需与模型一致）
EMBEDDING_DIM = settings.embedding_dim

CREATE_KNOWLEDGE_CHUNK_TABLE_SQL = f"""
CREATE TABLE IF NOT EXISTS {KNOWLEDGE_CHUNK_TABLE} (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'global',
    doc_title   VARCHAR(255) NOT NULL,
    page_number INT          NOT NULL DEFAULT 1,
    category    VARCHAR(64)  NOT NULL DEFAULT 'GENERAL',
    chunk_text  TEXT         NOT NULL,
    embedding   vector({EMBEDDING_DIM}),
    metadata    JSONB        NOT NULL DEFAULT '{{}}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"""

# HNSW 余弦索引（pgvector >= 0.5.0；在线索引，可对大表直接建）
CREATE_KNOWLEDGE_CHUNK_INDEX_SQL = f"""
CREATE INDEX IF NOT EXISTS idx_{KNOWLEDGE_CHUNK_TABLE}_embedding_hnsw
ON {KNOWLEDGE_CHUNK_TABLE} USING hnsw (embedding vector_cosine_ops);
"""

# 若需 IVFFlat（离线建索引、更适合超大表）可替换为：
# CREATE INDEX IF NOT EXISTS idx_{KNOWLEDGE_CHUNK_TABLE}_embedding_ivfflat
# ON {KNOWLEDGE_CHUNK_TABLE} USING ivfflat (embedding vector_cosine_ops)
# WITH (lists = 100);


async def init_knowledge_chunk_table() -> None:
    """异步初始化：确保 vector 扩展 + ``t_knowledge_chunk`` 表 + HNSW 索引就绪。

    使用一条独立异步连接执行，自动提交事务，调用方无需管理连接池。
    """
    conn = await psycopg.AsyncConnection.connect(settings.database_url)
    try:
        async with conn.transaction():
            await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            await conn.execute(CREATE_KNOWLEDGE_CHUNK_TABLE_SQL)
            await conn.execute(CREATE_KNOWLEDGE_CHUNK_INDEX_SQL)
    finally:
        await conn.close()


def init_knowledge_chunk_table_sync() -> None:
    """同步初始化：确保 vector 扩展 + ``t_knowledge_chunk`` 表 + HNSW 索引就绪。

    使用一条独立同步连接执行，自动提交事务；供 CLI / 运维脚本调用。
    """
    conn = psycopg.connect(settings.database_url)
    try:
        with conn.transaction():
            conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            conn.execute(CREATE_KNOWLEDGE_CHUNK_TABLE_SQL)
            conn.execute(CREATE_KNOWLEDGE_CHUNK_INDEX_SQL)
    finally:
        conn.close()
