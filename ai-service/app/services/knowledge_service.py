"""知识库管理服务：文档列表 / 上传入库 / 删除（基于 ``t_knowledge_chunk``）。

说明：
- 上传流程 = 文本解码 → 切片 → 向量化（未配置密钥时自动 Mock，可离线）→ 事务批量写入；
- 文档列表由 ``t_knowledge_chunk`` 按 ``doc_title`` 聚合推导，租户可见域 = 本租户 ∪ global；
- 删除仅清理该租户自身的切片行。
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import List, Sequence

from pgvector import Vector

from app.config import get_settings
from app.db.init_tables import KNOWLEDGE_CHUNK_TABLE, init_knowledge_chunk_table
from app.db.session import connection
from app.rag.embedder import Embedder, build_embedder
from app.rag.splitter import DocumentChunk, DocumentSplitter, SUPPORTED_TEXT_SUFFIXES
from app.schemas.knowledge import (
    KnowledgeDeleteResponse,
    KnowledgeDocItem,
    KnowledgeUploadResponse,
)

logger = logging.getLogger(__name__)

_INSERT_CHUNK_SQL = f"""
INSERT INTO {KNOWLEDGE_CHUNK_TABLE}
    (tenant_id, doc_title, page_number, category, chunk_text, embedding, metadata)
VALUES (%(tenant_id)s, %(doc_title)s, %(page_number)s, %(category)s,
        %(chunk_text)s, %(embedding)s, %(metadata)s::jsonb)
"""

_LIST_DOCS_SQL = f"""
SELECT row_number() OVER (ORDER BY MIN(created_at) DESC, doc_title)::int AS id,
       doc_title,
       tenant_id,
       COUNT(*)::int AS chunks,
       MIN(created_at) AS created_at,
       COALESCE(MIN(metadata->>'source'), '') AS source,
       COALESCE(MIN(category), 'GENERAL') AS category
FROM {KNOWLEDGE_CHUNK_TABLE}
WHERE (tenant_id = %(tenant_id)s OR tenant_id = 'global')
GROUP BY doc_title, tenant_id
ORDER BY MIN(created_at) DESC
"""

_DELETE_DOC_SQL = f"""
DELETE FROM {KNOWLEDGE_CHUNK_TABLE}
WHERE tenant_id = %(tenant_id)s AND doc_title = %(title)s
"""


class KnowledgeServiceError(Exception):
    """知识库管理业务错误（消息直接返回调用方）。"""


class KnowledgeService:
    """知识库管理服务（异步连接池执行 SQL）。"""

    def __init__(
        self,
        splitter: DocumentSplitter | None = None,
        embedder: Embedder | None = None,
    ) -> None:
        self.settings = get_settings()
        self.splitter = splitter or DocumentSplitter()
        self.embedder = embedder if embedder is not None else build_embedder()

    # ---------------------------------------------------------------- 列表

    async def list_docs(self, tenant_id: str) -> List[KnowledgeDocItem]:
        """列出可见文档（本租户 ∪ global），按最早入库时间倒序。"""
        await init_knowledge_chunk_table()
        rows: list = []
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(_LIST_DOCS_SQL, {"tenant_id": tenant_id or "global"})
                rows = await cur.fetchall()
        return [
            KnowledgeDocItem(
                id=int(r["id"]),
                title=str(r["doc_title"]),
                tenantId=str(r["tenant_id"]),
                source=str(r.get("source") or ""),
                category=str(r.get("category") or "GENERAL"),
                chunks=int(r["chunks"]),
                createdAt=r["created_at"].isoformat() if r["created_at"] is not None else "",
            )
            for r in rows
        ]
    # ---------------------------------------------------------------- 上传

    async def upload_text(
        self,
        tenant_id: str,
        filename: str,
        text: str,
        category: str = "GENERAL",
    ) -> KnowledgeUploadResponse:
        """校验文件名 → 切片 → 向量化 → 事务写入 t_knowledge_chunk。"""
        await init_knowledge_chunk_table()

        # 1) 格式白名单（当前上传端点支持 .txt/.md；PDF 请走 scripts/ingest_docs.py）
        suffix = _suffix_of(filename)
        if suffix not in SUPPORTED_TEXT_SUFFIXES:
            raise KnowledgeServiceError(
                f"上传端点仅支持 {sorted(SUPPORTED_TEXT_SUFFIXES)} 纯文本；"
                f"收到 {suffix or '(无扩展名)'}（{filename}）。PDF 请使用 scripts/ingest_docs.py。"
            )

        title = filename.rsplit(".", 1)[0].strip() or filename.strip()
        body = (text or "").strip()
        if not body:
            raise KnowledgeServiceError("文档正文为空，无可切片内容")

        # 2) 切片（DocumentChunk.doc_title 已带标题）
        chunks = self.splitter.split_text(body, doc_title=title)
        if not chunks:
            raise KnowledgeServiceError("切片结果为空，请检查文档正文是否过短")

        # 3) 向量化：可能调用远端 Embedding 端点，放入线程池避免阻塞事件循环
        vectors: Sequence[Vector] = await asyncio.to_thread(
            self.embedder.embed_texts, [c.chunk_text for c in chunks]
        )
        if len(vectors) != len(chunks):
            raise KnowledgeServiceError(
                f"向量化数量不一致 chunks={len(chunks)} vectors={len(vectors)}"
            )

        # 4) 事务批量写入（全部成功才落库；失败整体回滚）
        effective_category = (category or "GENERAL").strip().upper() or "GENERAL"
        await self._insert_chunks(tenant_id, filename, title, effective_category, chunks, vectors)

        logger.info(
            "knowledge upload ok title=%s tenant=%s chunks=%d embedding=%s",
            title,
            tenant_id,
            len(chunks),
            "Mock" if self.embedder.is_mock else "Real",
        )
        return KnowledgeUploadResponse(
            title=title,
            tenantId=tenant_id,
            category=effective_category,
            chunks=len(chunks),
            embeddingMode="Mock" if self.embedder.is_mock else "Real",
        )

    async def _insert_chunks(
        self,
        tenant_id: str,
        source_file: str,
        title: str,
        category: str,
        chunks: List[DocumentChunk],
        vectors: Sequence[Vector],
    ) -> None:
        """以单事务写入全部切片（含 metadata JSON）。"""
        async with connection() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    for chunk, vec in zip(chunks, vectors):
                        metadata = json.dumps(
                            {
                                "source": source_file,
                                "category": category,
                                "tenantId": tenant_id,
                                "docTitle": title,
                            },
                            ensure_ascii=False,
                        )
                        await cur.execute(
                            _INSERT_CHUNK_SQL,
                            {
                                "tenant_id": tenant_id,
                                "doc_title": title,
                                "page_number": chunk.page_number,
                                "category": category,
                                "chunk_text": chunk.chunk_text,
                                "embedding": vec,
                                "metadata": metadata,
                            },
                        )

    # ---------------------------------------------------------------- 删除

    async def delete_doc(self, tenant_id: str, title: str) -> KnowledgeDeleteResponse:
        """删除指定租户下名为 title 的文档全部切片行。"""
        await init_knowledge_chunk_table()
        async with connection() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    await cur.execute(
                        _DELETE_DOC_SQL,
                        {"tenant_id": tenant_id, "title": title},
                    )
                    deleted = cur.rowcount if cur.rowcount is not None else 0
        logger.info("knowledge delete title=%s tenant=%s deleted=%d", title, tenant_id, deleted)
        return KnowledgeDeleteResponse(deleted=int(deleted))


def _suffix_of(filename: str) -> str:
    """取文件名后缀（含点）；文件名不得为空或含路径分隔符。"""
    name = (filename or "").strip()
    if not name:
        raise KnowledgeServiceError("文件名不能为空")
    if "/" in name or "\\" in name:
        raise KnowledgeServiceError("文件名不能包含路径分隔符")
    _, dot, suffix = name.rpartition(".")
    if not dot:
        return ""
    return suffix.lower()


__all__ = ["KnowledgeService", "KnowledgeServiceError"]
