"""关键词召回器：中文 2-gram 倒排 + ILIKE 匹配，作为向量召回的双路补充。

设计要点：
- 对查询文本提取中文连续 2-gram 与英文/数字整词作为关键词（零额外依赖）；
- 对 ``t_knowledge_chunk.chunk_text`` 做 ILIKE 子串匹配，命中越多得分越高；
- 归一化得分 = 命中关键词数 / 关键词总数，落在 [0,1]，与向量余弦同尺度，便于融合；
- 租户可见域（本租户 ∪ global）与分类过滤与向量召回保持一致。
"""

from __future__ import annotations

import logging
import re
from typing import Any, Dict, List, Optional

from app.config import Settings, get_settings
from app.db.init_tables import KNOWLEDGE_CHUNK_TABLE
from app.db.session import connection

logger = logging.getLogger(__name__)

# 默认检索参数
DEFAULT_TOP_K = 3
DEFAULT_MIN_SCORE = 0.3
GENERAL_CATEGORY = "GENERAL"
_PREFETCH_FACTOR = 10
_PREFETCH_MIN = 50

# 仅保留中英文与数字，其余视作分隔符
_PUNCT_RE = re.compile(r"[^\u4e00-\u9fa5a-zA-Z0-9]+")

# 高频无信息量停用词（疑问词/虚词），避免其产生噪音命中
STOPWORDS = frozenset({
    "如何", "怎么", "什么", "请问", "是否", "可以", "应该", "哪些", "多少",
    "一个", "这个", "那个", "因为", "所以", "以及", "或者", "还有", "进行",
    "需要", "一下", "知道", "告诉", "为什么", "回事", "时候", "地方",
})


def extract_bigrams(text: str) -> List[str]:
    """把查询文本提取为去重后的关键词集合（中文 2-gram + 英文/数字整词）。"""
    cleaned = _PUNCT_RE.sub(" ", text or "")
    grams: List[str] = []
    for seg in cleaned.split():
        if len(seg) < 2:
            continue
        if re.fullmatch(r"[a-zA-Z0-9]+", seg):
            grams.append(seg.lower())
        else:
            grams.extend(seg[i : i + 2] for i in range(len(seg) - 1))
    seen: set[str] = set()
    result: List[str] = []
    for g in grams:
        if g in seen or g in STOPWORDS:
            continue
        seen.add(g)
        result.append(g)
    return result


class KeywordRetriever:
    """基于 ILIKE 子串匹配的关键词召回器。"""

    def __init__(self, settings: Optional[Settings] = None) -> None:
        self.settings = settings or get_settings()

    async def search(
        self,
        query_text: str,
        tenant_id: str = "global",
        category: Optional[str] = None,
        top_k: int = DEFAULT_TOP_K,
        min_score: float = DEFAULT_MIN_SCORE,
    ) -> List[Dict[str, Any]]:
        """执行关键词召回，返回按命中比例降序、且 ``score >= min_score`` 的至多 ``top_k`` 条。"""
        grams = extract_bigrams(query_text)
        if not grams:
            logger.warning("关键词提取为空，跳过关键词召回 tenant=%s", tenant_id)
            return []

        n = len(grams)
        cond = " + ".join(
            f"(CASE WHEN chunk_text ILIKE %(kw{i})s THEN 1 ELSE 0 END)" for i in range(n)
        )
        any_where = " OR ".join(f"chunk_text ILIKE %(kw{i})s" for i in range(n))

        where = ["(tenant_id = %(tenant_id)s OR tenant_id = 'global')"]
        params: Dict[str, Any] = {"tenant_id": tenant_id or "global"}
        for i, g in enumerate(grams):
            params[f"kw{i}"] = f"%{g}%"
        if category and str(category).strip().upper() != GENERAL_CATEGORY:
            where.append("category = %(category)s")
            params["category"] = str(category).strip()
        where_sql = " AND ".join(where)
        params["prefetch"] = max(_PREFETCH_MIN, top_k * _PREFETCH_FACTOR)

        sql = f"""
SELECT id, tenant_id, doc_title, page_number, category, chunk_text,
       ({cond}) AS kw_hits
FROM {KNOWLEDGE_CHUNK_TABLE}
WHERE {where_sql}
  AND ({any_where})
ORDER BY kw_hits DESC, id ASC
LIMIT %(prefetch)s
"""
        rows: List[Dict[str, Any]] = []
        try:
            async with connection() as conn:
                async with conn.cursor() as cur:
                    await cur.execute(sql, params)
                    rows = await cur.fetchall()
        except Exception:  # noqa: BLE001 - 检索失败向上抛由编排层统一降级
            logger.exception("关键词召回失败 tenant=%s category=%s", tenant_id, category)
            raise

        top_k = max(1, int(top_k))
        min_score = float(min_score)
        hits: List[Dict[str, Any]] = []
        for row in rows:
            kw_hits = int(row.get("kw_hits") or 0)
            score = round(kw_hits / n, 6)
            if score < min_score:
                continue
            hits.append({
                "id": row.get("id"),
                "tenant_id": row.get("tenant_id") or tenant_id,
                "doc_title": row.get("doc_title"),
                "page_number": row.get("page_number"),
                "category": row.get("category"),
                "chunk_text": row.get("chunk_text"),
                "score": score,
            })
            if len(hits) >= top_k:
                break

        logger.info(
            "关键词召回完成 tenant=%s 关键词=%d 命中=%d/%d (top_k=%d min_score=%.2f)",
            tenant_id, n, len(hits), len(rows), top_k, min_score,
        )
        return hits


__all__ = [
    "KeywordRetriever",
    "extract_bigrams",
    "DEFAULT_TOP_K",
    "DEFAULT_MIN_SCORE",
]
