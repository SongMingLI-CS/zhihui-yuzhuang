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
    "KNOWLEDGE_DOC_TABLE",
    "KNOWLEDGE_DOC_VERSION_TABLE",
    "EMBEDDING_DIM",
    "CREATE_KNOWLEDGE_CHUNK_TABLE_SQL",
    "CREATE_KNOWLEDGE_CHUNK_INDEX_SQL",
    "CREATE_KNOWLEDGE_DOC_TABLE_SQL",
    "CREATE_KNOWLEDGE_DOC_VERSION_TABLE_SQL",
    "CREATE_KNOWLEDGE_DOC_INDEX_SQL",
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

# ============================================================
# 阶段 F：文档登记表（当前版本元数据 + 版本历史 + 审核状态）
# ============================================================

KNOWLEDGE_DOC_TABLE = "t_knowledge_doc"
KNOWLEDGE_DOC_VERSION_TABLE = "t_knowledge_doc_version"

CREATE_KNOWLEDGE_DOC_TABLE_SQL = f"""
CREATE TABLE IF NOT EXISTS {KNOWLEDGE_DOC_TABLE} (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    doc_title       VARCHAR(255) NOT NULL,
    source_file     VARCHAR(255) NOT NULL,
    content_type    VARCHAR(64)  NOT NULL DEFAULT 'text/plain',
    content_sha256  CHAR(64)     NOT NULL,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    category        VARCHAR(64)  NOT NULL DEFAULT 'GENERAL',
    version         INT          NOT NULL DEFAULT 1,
    pages           INT          NOT NULL DEFAULT 1,
    chunks          INT          NOT NULL DEFAULT 0,
    review_status   VARCHAR(24)  NOT NULL DEFAULT 'PENDING_REVIEW',
    uploaded_by     VARCHAR(64),
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by     VARCHAR(64),
    reviewed_at     TIMESTAMPTZ,
    review_comment  VARCHAR(512),
    CONSTRAINT uk_knowledge_doc_tenant_title UNIQUE (tenant_id, doc_title)
);
"""

CREATE_KNOWLEDGE_DOC_VERSION_TABLE_SQL = f"""
CREATE TABLE IF NOT EXISTS {KNOWLEDGE_DOC_VERSION_TABLE} (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    doc_title       VARCHAR(255) NOT NULL,
    version         INT          NOT NULL,
    content_sha256  CHAR(64)     NOT NULL,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    chunks          INT          NOT NULL DEFAULT 0,
    uploaded_by     VARCHAR(64),
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_doc_version UNIQUE (tenant_id, doc_title, version)
);
"""

CREATE_KNOWLEDGE_DOC_INDEX_SQL = (
    f"CREATE INDEX IF NOT EXISTS idx_{KNOWLEDGE_DOC_TABLE}_tenant "
    f"ON {KNOWLEDGE_DOC_TABLE} (tenant_id, uploaded_at DESC);"
)



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
            await conn.execute(CREATE_KNOWLEDGE_DOC_TABLE_SQL)
            await conn.execute(CREATE_KNOWLEDGE_DOC_VERSION_TABLE_SQL)
            await conn.execute(CREATE_KNOWLEDGE_DOC_INDEX_SQL)
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
            conn.execute(CREATE_KNOWLEDGE_DOC_TABLE_SQL)
            conn.execute(CREATE_KNOWLEDGE_DOC_VERSION_TABLE_SQL)
            conn.execute(CREATE_KNOWLEDGE_DOC_INDEX_SQL)
    finally:
        conn.close()
