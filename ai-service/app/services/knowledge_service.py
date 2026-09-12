"""知识库管理服务：文档登记（版本/哈希/上传者/审核）+ 上传入库 + 列表 + 删除。

阶段 F 增强：
- 上传按扩展名隔离解析（txt/md/pdf），校验大小、魔数、页数（见 ``app.rag.document_loader``）；
- 每次上传登记 ``t_knowledge_doc``（当前版本）+ ``t_knowledge_doc_version``（版本历史），
  记录内容 SHA-256、上传者、页数、切片数，审核状态重置为 PENDING_REVIEW；
- 列表在 chunk 聚合基础上合并登记表元数据（历史数据无登记行时回退默认值，向后兼容）；
- 提供审核（APPROVED/REJECTED）与版本历史查询；租户/上传者/审核人均由调用方
  从已验证身份传入，服务不信任请求体。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from typing import Dict, List, Sequence

from pgvector import Vector

from app.config import get_settings
from app.db.init_tables import (
    KNOWLEDGE_CHUNK_TABLE,
    KNOWLEDGE_DOC_TABLE,
    KNOWLEDGE_DOC_VERSION_TABLE,
    init_knowledge_chunk_table,
)
from app.db.session import connection
from app.rag.document_loader import (
    DocumentValidationError,
    ExtractedPage,
    load_document,
    suffix_of,
)
from app.rag.embedder import Embedder, build_embedder
from app.rag.splitter import DocumentChunk, DocumentSplitter
from app.schemas.knowledge import (
    KnowledgeDeleteResponse,
    KnowledgeDocItem,
    KnowledgeReviewResponse,
    KnowledgeUploadResponse,
    KnowledgeVersionItem,
)

logger = logging.getLogger(__name__)

_CONTENT_TYPES = {
    "txt": "text/plain",
    "md": "text/markdown",
    "markdown": "text/markdown",
    "pdf": "application/pdf",
}

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

_LIST_DOC_META_SQL = f"""
SELECT tenant_id, doc_title, source_file, content_sha256, file_size, category,
       version, pages, chunks, review_status, uploaded_by, uploaded_at,
       reviewed_by, reviewed_at, review_comment
FROM {KNOWLEDGE_DOC_TABLE}
WHERE (tenant_id = %(tenant_id)s OR tenant_id = 'global')
"""

_NEXT_VERSION_SQL = f"""
SELECT COALESCE(MAX(version), 0) + 1 AS next_version
FROM {KNOWLEDGE_DOC_TABLE}
WHERE tenant_id = %(tenant_id)s AND doc_title = %(doc_title)s
"""

_UPSERT_DOC_SQL = f"""
INSERT INTO {KNOWLEDGE_DOC_TABLE}
    (tenant_id, doc_title, source_file, content_type, content_sha256, file_size,
     category, version, pages, chunks, review_status, uploaded_by, uploaded_at)
VALUES (%(tenant_id)s, %(doc_title)s, %(source_file)s, %(content_type)s, %(content_sha256)s,
        %(file_size)s, %(category)s, %(version)s, %(pages)s, %(chunks)s,
        'PENDING_REVIEW', %(uploaded_by)s, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, doc_title) DO UPDATE SET
    source_file     = EXCLUDED.source_file,
    content_type    = EXCLUDED.content_type,
    content_sha256  = EXCLUDED.content_sha256,
    file_size       = EXCLUDED.file_size,
    category        = EXCLUDED.category,
    version         = EXCLUDED.version,
    pages           = EXCLUDED.pages,
    chunks          = EXCLUDED.chunks,
    review_status   = 'PENDING_REVIEW',
    uploaded_by     = EXCLUDED.uploaded_by,
    uploaded_at     = CURRENT_TIMESTAMP,
    reviewed_by     = NULL,
    reviewed_at     = NULL,
    review_comment  = NULL
"""

_INSERT_VERSION_SQL = f"""
INSERT INTO {KNOWLEDGE_DOC_VERSION_TABLE}
    (tenant_id, doc_title, version, content_sha256, file_size, chunks, uploaded_by, uploaded_at)
VALUES (%(tenant_id)s, %(doc_title)s, %(version)s, %(content_sha256)s, %(file_size)s,
        %(chunks)s, %(uploaded_by)s, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, doc_title, version) DO NOTHING
"""

_REVIEW_SQL = f"""
UPDATE {KNOWLEDGE_DOC_TABLE}
SET review_status = %(status)s,
    reviewed_by = %(reviewed_by)s,
    reviewed_at = CURRENT_TIMESTAMP,
    review_comment = %(comment)s
WHERE tenant_id = %(tenant_id)s AND doc_title = %(doc_title)s
"""

_LIST_VERSIONS_SQL = f"""
SELECT version, content_sha256, file_size, chunks, uploaded_by, uploaded_at
FROM {KNOWLEDGE_DOC_VERSION_TABLE}
WHERE tenant_id = %(tenant_id)s AND doc_title = %(doc_title)s
ORDER BY version DESC
"""

_DELETE_DOC_SQL = f"""
DELETE FROM {KNOWLEDGE_CHUNK_TABLE}
WHERE tenant_id = %(tenant_id)s AND doc_title = %(title)s
"""

_DELETE_DOC_META_SQL = f"""
DELETE FROM {KNOWLEDGE_DOC_TABLE}
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
        """列出可见文档（本租户 ∪ global），合并登记表元数据（审核/版本/哈希/上传者）。"""
        await init_knowledge_chunk_table()
        effective_tenant = tenant_id or "global"
        rows: list = []
        meta_rows: list = []
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(_LIST_DOCS_SQL, {"tenant_id": effective_tenant})
                rows = await cur.fetchall()
                try:
                    await cur.execute(_LIST_DOC_META_SQL, {"tenant_id": effective_tenant})
                    meta_rows = await cur.fetchall()
                except Exception as exc:  # noqa: BLE001 - 登记表缺失/异常时回退
                    logger.warning("知识库登记表读取失败（按默认元数据回退）：%s", exc)

        meta: Dict[tuple, dict] = {
            (str(m["tenant_id"]), str(m["doc_title"])): m for m in meta_rows
        }

        items: List[KnowledgeDocItem] = []
        for r in rows:
            key = (str(r["tenant_id"]), str(r["doc_title"]))
            m = meta.get(key)
            reviewed_at = m.get("reviewed_at") if m else None
            items.append(
                KnowledgeDocItem(
                    id=int(r["id"]),
                    title=str(r["doc_title"]),
                    tenantId=str(r["tenant_id"]),
                    source=str((m.get("source_file") if m else None) or r.get("source") or ""),
                    category=str((m.get("category") if m else None) or r.get("category") or "GENERAL"),
                    chunks=int(r["chunks"]),
                    createdAt=r["created_at"].isoformat() if r["created_at"] is not None else "",
                    version=int(m.get("version")) if m else 1,
                    contentSha256=str((m.get("content_sha256") if m else "") or ""),
                    fileSize=int(m.get("file_size")) if m else 0,
                    pages=int(m.get("pages")) if m else 1,
                    uploadedBy=str((m.get("uploaded_by") if m else "") or ""),
                    reviewStatus=str((m.get("review_status") if m else None) or "PENDING_REVIEW"),
                    reviewedBy=str((m.get("reviewed_by") if m else "") or ""),
                    reviewedAt=reviewed_at.isoformat() if reviewed_at is not None else "",
                    reviewComment=str((m.get("review_comment") if m else "") or ""),
                )
            )
        return items

    # ---------------------------------------------------------------- 上传

    async def upload_document(
        self,
        tenant_id: str,
        filename: str,
        raw: bytes,
        category: str = "GENERAL",
        uploaded_by: str = "",
    ) -> KnowledgeUploadResponse:
        """校验并解析文档 → 切片 → 向量化 → 单事务写入切片 + 登记文档版本。"""
        await init_knowledge_chunk_table()

        # 1) 隔离解析（扩展名/大小/魔数/页数校验在此统一执行）
        try:
            pages: List[ExtractedPage] = load_document(filename, raw)
        except DocumentValidationError as exc:
            raise KnowledgeServiceError(str(exc)) from exc

        title = filename.rsplit(".", 1)[0].strip() or filename.strip()
        if not title:
            raise KnowledgeServiceError("无法从文件名推导文档标题")

        # 2) 逐页切片（保留真实页码，便于引用定位）
        chunks: List[DocumentChunk] = []
        offset = 0
        for page in pages:
            page_chunks = self.splitter.split_text(
                page.text,
                page_number=page.page_number,
                chunk_index_offset=offset,
                doc_title=title,
            )
            offset += len(page_chunks)
            chunks.extend(page_chunks)
        if not chunks:
            raise KnowledgeServiceError("切片结果为空，请检查文档正文是否过短")

        # 3) 向量化（可能调用远端端点，放入线程池避免阻塞事件循环）
        try:
            vectors: Sequence[Vector] = await asyncio.to_thread(
                self.embedder.embed_texts, [c.chunk_text for c in chunks]
            )
        except Exception as exc:  # noqa: BLE001 - 含 EmbeddingUnavailableError
            raise KnowledgeServiceError(f"向量化失败：{exc}") from exc
        if len(vectors) != len(chunks):
            raise KnowledgeServiceError(
                f"向量化数量不一致 chunks={len(chunks)} vectors={len(vectors)}"
            )

        effective_category = (category or "GENERAL").strip().upper() or "GENERAL"
        content_sha256 = hashlib.sha256(raw).hexdigest()
        suffix = suffix_of(filename)
        content_type = _CONTENT_TYPES.get(suffix, "application/octet-stream")

        # 4) 单事务：写切片 + 登记文档与版本
        version = await self._register_and_insert(
            tenant_id=tenant_id,
            filename=filename,
            title=title,
            category=effective_category,
            content_type=content_type,
            content_sha256=content_sha256,
            file_size=len(raw),
            pages=len(pages),
            chunks=chunks,
            vectors=vectors,
            uploaded_by=uploaded_by,
        )

        logger.info(
            "knowledge upload ok title=%s tenant=%s chunks=%d pages=%d version=%d embedding=%s",
            title,
            tenant_id,
            len(chunks),
            len(pages),
            version,
            self.embedder.mode,
        )
        return KnowledgeUploadResponse(
            title=title,
            tenantId=tenant_id,
            category=effective_category,
            chunks=len(chunks),
            embeddingMode=self.embedder.mode,
            version=version,
            pages=len(pages),
            fileSize=len(raw),
            contentSha256=content_sha256,
            reviewStatus="PENDING_REVIEW",
        )

    async def _register_and_insert(
        self,
        *,
        tenant_id: str,
        filename: str,
        title: str,
        category: str,
        content_type: str,
        content_sha256: str,
        file_size: int,
        pages: int,
        chunks: List[DocumentChunk],
        vectors: Sequence[Vector],
        uploaded_by: str,
    ) -> int:
        """单事务：登记文档（当前版本）+ 追加版本历史 + 写入全部切片；返回版本号。"""
        async with connection() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    await cur.execute(
                        _NEXT_VERSION_SQL, {"tenant_id": tenant_id, "doc_title": title}
                    )
                    row = await cur.fetchone()
                    version = int(row["next_version"]) if row else 1

                    await cur.execute(
                        _UPSERT_DOC_SQL,
                        {
                            "tenant_id": tenant_id,
                            "doc_title": title,
                            "source_file": filename,
                            "content_type": content_type,
                            "content_sha256": content_sha256,
                            "file_size": file_size,
                            "category": category,
                            "version": version,
                            "pages": pages,
                            "chunks": len(chunks),
                            "uploaded_by": uploaded_by,
                        },
                    )
                    await cur.execute(
                        _INSERT_VERSION_SQL,
                        {
                            "tenant_id": tenant_id,
                            "doc_title": title,
                            "version": version,
                            "content_sha256": content_sha256,
                            "file_size": file_size,
                            "chunks": len(chunks),
                            "uploaded_by": uploaded_by,
                        },
                    )
                    # 覆盖上传：先清掉旧版本切片，避免同一文档新旧切片混杂
                    await cur.execute(
                        _DELETE_DOC_SQL, {"tenant_id": tenant_id, "title": title}
                    )
                    for chunk, vec in zip(chunks, vectors):
                        metadata = json.dumps(
                            {
                                "source": filename,
                                "category": category,
                                "tenantId": tenant_id,
                                "docTitle": title,
                                "version": version,
                                "contentSha256": content_sha256,
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
        return version

    # ---------------------------------------------------------------- 审核

    async def review_doc(
        self,
        tenant_id: str,
        title: str,
        status: str,
        reviewed_by: str,
        comment: str = "",
    ) -> KnowledgeReviewResponse:
        """审核文档（APPROVED/REJECTED）；未登记文档返回业务错误。"""
        await init_knowledge_chunk_table()
        if status not in {"APPROVED", "REJECTED"}:
            raise KnowledgeServiceError("审核结论仅支持 APPROVED / REJECTED")
        async with connection() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    await cur.execute(
                        _REVIEW_SQL,
                        {
                            "status": status,
                            "reviewed_by": reviewed_by,
                            "comment": comment or None,
                            "tenant_id": tenant_id,
                            "doc_title": title,
                        },
                    )
                    if (cur.rowcount or 0) == 0:
                        raise KnowledgeServiceError("文档不存在或不属于该租户")
        logger.info(
            "knowledge review title=%s tenant=%s status=%s by=%s",
            title, tenant_id, status, reviewed_by,
        )
        from datetime import datetime, timezone  # noqa: PLC0415

        return KnowledgeReviewResponse(
            title=title,
            tenantId=tenant_id,
            reviewStatus=status,  # type: ignore[arg-type]
            reviewedBy=reviewed_by,
            reviewedAt=datetime.now(timezone.utc).isoformat(),
            comment=comment or "",
        )

    # ---------------------------------------------------------------- 版本

    async def list_versions(self, tenant_id: str, title: str) -> List[KnowledgeVersionItem]:
        """列出某文档的版本历史（倒序）。"""
        await init_knowledge_chunk_table()
        rows: list = []
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    _LIST_VERSIONS_SQL, {"tenant_id": tenant_id, "doc_title": title}
                )
                rows = await cur.fetchall()
        return [
            KnowledgeVersionItem(
                version=int(r["version"]),
                contentSha256=str(r["content_sha256"] or ""),
                fileSize=int(r["file_size"] or 0),
                chunks=int(r["chunks"] or 0),
                uploadedBy=str(r["uploaded_by"] or ""),
                uploadedAt=r["uploaded_at"].isoformat() if r["uploaded_at"] is not None else "",
            )
            for r in rows
        ]

    # ---------------------------------------------------------------- 删除

    async def delete_doc(self, tenant_id: str, title: str) -> KnowledgeDeleteResponse:
        """删除该租户下 title 的切片与登记行（版本历史保留作审计）。"""
        await init_knowledge_chunk_table()
        async with connection() as conn:
            async with conn.transaction():
                async with conn.cursor() as cur:
                    await cur.execute(
                        _DELETE_DOC_SQL,
                        {"tenant_id": tenant_id, "title": title},
                    )
                    deleted = cur.rowcount if cur.rowcount is not None else 0
                    try:
                        await cur.execute(
                            _DELETE_DOC_META_SQL,
                            {"tenant_id": tenant_id, "title": title},
                        )
                    except Exception as exc:  # noqa: BLE001 - 登记表缺失时忽略
                        logger.warning("删除知识库登记行失败（已忽略）：%s", exc)
        logger.info("knowledge delete title=%s tenant=%s deleted=%d", title, tenant_id, deleted)
        return KnowledgeDeleteResponse(deleted=int(deleted))


__all__ = ["KnowledgeService", "KnowledgeServiceError"]



