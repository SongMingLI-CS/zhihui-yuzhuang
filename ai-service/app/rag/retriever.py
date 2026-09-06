"""知识库向量检索器（RAG 检索内核）。

:class:`KnowledgeRetriever` 将查询文本交给 :class:`app.rag.embedder.Embedder`
向量化后，在 ``t_knowledge_chunk`` 上做 pgvector 余弦相似度检索
（``embedding <=> 查询向量``），并支持：

- 多租户回退：``WHERE (tenant_id = :tenant OR tenant_id = 'global')``；
- 分类二次过滤：传入 ``category`` 且非 ``GENERAL`` 时按分类精确过滤；
- 阈值截断：仅返回 ``score = 1 - 余弦距离 >= min_score`` 的切片并截取 ``top_k`` 条。

返回的每条命中为 ``dict``：:

    {
        "id": int, "tenant_id": str, "doc_title": str,
        "page_number": int, "category": str, "chunk_text": str,
        "score": float,   # 0~1，余弦相似度（已裁剪）
    }

注意：查询向量必须用 :class:`pgvector.Vector` 包装后作为参数传入 SQL
（严禁裸 Python ``list``，否则触发 ``DatatypeMismatch``）。
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from app.config import Settings, get_settings
from app.db.init_tables import KNOWLEDGE_CHUNK_TABLE
from app.db.session import Vector, connection
from app.rag.embedder import Embedder, build_embedder
from app.rag.keyword_retriever import KeywordRetriever

logger = logging.getLogger(__name__)

# 默认检索参数（与任务契约一致）
DEFAULT_TOP_K = 3
DEFAULT_MIN_SCORE = 0.65
# 通用分类：传入 GENERAL 时不按 category 二次过滤（通用问题可在全分类内检索）
GENERAL_CATEGORY = "GENERAL"
# SQL 预取上限：先按余弦距离取一批近邻，再到内存中按 min_score 过滤、截取 top_k，
# 兼顾索引命中与阈值语义（最终返回条数 <= top_k）。
_PREFETCH_FACTOR = 10
_PREFETCH_MIN = 50


class KnowledgeRetriever:
    """基于 pgvector 余弦相似度的知识切片检索器。"""

    def __init__(
        self,
        embedder: Optional[Embedder] = None,
        *,
        settings: Optional[Settings] = None,
    ) -> None:
        cfg = settings or get_settings()
        self.settings = cfg
        self.embedder = embedder if embedder is not None else build_embedder()
        self.keyword_retriever = KeywordRetriever(settings=cfg)
        self.keyword_min_score = float(getattr(cfg, "rag_keyword_min_score", 0.3))

    @property
    def is_mock(self) -> bool:
        """检索器底层 Embedder 是否处于 Mock 模式（供测试 / 运维判断）。"""
        return bool(getattr(self.embedder, "is_mock", False))

    # ------------------------------------------------------------ 对外检索入口

    async def search(
        self,
        query_text: str,
        tenant_id: str = "global",
        category: Optional[str] = None,
        top_k: int = DEFAULT_TOP_K,
        min_score: float = DEFAULT_MIN_SCORE,
    ) -> List[Dict[str, Any]]:
        """执行向量检索，返回按相似度降序、且 ``score >= min_score`` 的至多 ``top_k`` 条命中。"""
        query_text = (query_text or "").strip()
        if not query_text:
            logger.warning("检索查询为空，跳过")
            return []

        top_k = max(1, int(top_k))
        min_score = float(min_score)

        # a. 查询文本 -> 查询向量（Embedder 输出 pgvector.Vector，可直接作 SQL 参数）
        vectors = self.embedder.embed_texts([query_text])
        query_vec: Vector = vectors[0]

        # b. 构造 SQL（余弦距离 -> 相似度 score）
        sql, params = self._build_search_sql(tenant_id, category)
        params["q_vec"] = query_vec
        params["prefetch"] = max(_PREFETCH_MIN, top_k * _PREFETCH_FACTOR)

        rows: List[Dict[str, Any]] = []
        try:
            async with connection() as conn:
                async with conn.cursor() as cur:
                    await cur.execute(sql, params)
                    rows = await cur.fetchall()
        except Exception:  # noqa: BLE001 - 检索失败向上抛由编排层统一降级
            logger.exception(
                "向量检索失败 tenant=%s category=%s top_k=%d min_score=%.2f",
                tenant_id,
                category,
                top_k,
                min_score,
            )
            raise

        # c/d. 内存中：score 裁剪到 0~1 -> 过滤 >= min_score -> 截取 top_k
        hits: List[Dict[str, Any]] = []
        for row in rows:
            score = self._clamp_score(row.get("score"))
            if score < min_score:
                continue
            hits.append(
                {
                    "id": row.get("id"),
                    "tenant_id": row.get("tenant_id") or tenant_id,
                    "doc_title": row.get("doc_title"),
                    "page_number": row.get("page_number"),
                    "category": row.get("category"),
                    "chunk_text": row.get("chunk_text"),
                    "score": round(score, 6),
                }
            )
            if len(hits) >= top_k:
                break

        logger.info(
            "向量检索完成 tenant=%s category=%s 命中=%d/%d (top_k=%d min_score=%.2f)",
            tenant_id,
            category or GENERAL_CATEGORY,
            len(hits),
            len(rows),
            top_k,
            min_score,
        )
        return hits

    # ------------------------------------------------------------ 双路召回融合

    async def search_hybrid(
        self,
        query_text: str,
        tenant_id: str = "global",
        category: Optional[str] = None,
        top_k: int = DEFAULT_TOP_K,
        min_score: float = DEFAULT_MIN_SCORE,
    ) -> List[Dict[str, Any]]:
        """双路召回（向量 + 关键词）并 RRF 融合重排。

        向量召回与关键词召回各自取 ``top_k * 2`` 条候选，再用倒数排名融合（RRF）
        合并去重，返回按融合分降序的前 ``top_k`` 条。每条命中的 ``score`` 保留其
        原始相似度（向量余弦 / 关键词命中比例，均 ∈[0,1]），供 citation 的
        ``similarityScore`` 使用；排序依据为融合后的 ``rrf`` 得分。
        """
        candidate_k = max(3, int(top_k) * 2)
        vector_hits = await self.search(
            query_text=query_text,
            tenant_id=tenant_id,
            category=category,
            top_k=candidate_k,
            min_score=min_score,
        )
        keyword_hits = await self.keyword_retriever.search(
            query_text=query_text,
            tenant_id=tenant_id,
            category=category,
            top_k=candidate_k,
            min_score=self.keyword_min_score,
        )
        merged = reciprocal_rank_fusion([vector_hits, keyword_hits], k=RRF_K)
        return merged[: max(1, int(top_k))]

    # ------------------------------------------------------------ SQL 组装

    def _build_search_sql(
        self, tenant_id: str, category: Optional[str]
    ) -> tuple[str, Dict[str, Any]]:
        """组装检索 SQL（按距离升序取预取上限，评分在 SELECT 内计算）。

        返回 ``(sql, params)``；``q_vec`` / ``prefetch`` 由调用方补充。
        """
        where = ["(tenant_id = %(tenant_id)s OR tenant_id = 'global')"]
        params: Dict[str, Any] = {"tenant_id": tenant_id or "global"}

        if category and str(category).strip().upper() != GENERAL_CATEGORY:
            where.append("category = %(category)s")
            params["category"] = str(category).strip()

        where_sql = " AND ".join(where)
        sql = f"""
SELECT id,
       tenant_id,
       doc_title,
       page_number,
       category,
       chunk_text,
       (1 - (embedding <=> %(q_vec)s::vector)) AS score
FROM {KNOWLEDGE_CHUNK_TABLE}
WHERE {where_sql}
ORDER BY embedding <=> %(q_vec)s::vector ASC
LIMIT %(prefetch)s
"""
        return sql, params

    # ------------------------------------------------------------ 内部工具

    @staticmethod
    def _clamp_score(score: Any) -> float:
        """余弦相似度理论上处于 [-1, 1]，裁剪到 [0, 1] 以满足 Citation 校验。"""
        try:
            value = float(score)
        except (TypeError, ValueError):
            return 0.0
        if value < 0.0:
            return 0.0
        if value > 1.0:
            return 1.0
        return value


__all__ = [
    "KnowledgeRetriever",
    "reciprocal_rank_fusion",
    "RRF_K",
    "DEFAULT_TOP_K",
    "DEFAULT_MIN_SCORE",
    "GENERAL_CATEGORY",
]


# RRF 融合常量（rank 越大贡献越小，k 控制平滑程度）
RRF_K = 60


def reciprocal_rank_fusion(
    result_lists: List[List[Dict[str, Any]]], k: int = RRF_K
) -> List[Dict[str, Any]]:
    """倒数排名融合（RRF）：对多个召回列表按排名加权合并去重。

    每个结果列表内按已有顺序（相似度/命中比例降序）作为排名，第 ``rank`` 位贡献
    ``1/(k+rank+1)`` 分；同一 chunk 出现在多路时分数累加。返回按融合分降序的结果。
    """
    fused: Dict[Any, Dict[str, Any]] = {}
    for lst in result_lists:
        for rank, hit in enumerate(lst):
            cid = hit.get("id")
            if cid is None:
                continue
            if cid not in fused:
                fused[cid] = dict(hit)
                fused[cid]["rrf"] = 0.0
            fused[cid]["rrf"] = fused[cid]["rrf"] + 1.0 / (k + rank + 1)
    return sorted(fused.values(), key=lambda h: float(h["rrf"]), reverse=True)
