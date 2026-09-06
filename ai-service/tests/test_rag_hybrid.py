"""双路召回核心组件单元测试（extract_bigrams + RRF 融合，无需数据库）。

运行方式：
    python -m pytest tests/test_rag_hybrid.py
"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.rag.keyword_retriever import extract_bigrams  # noqa: E402
from app.rag.retriever import reciprocal_rank_fusion  # noqa: E402


def test_extract_bigrams_chinese():
    grams = extract_bigrams("小麦返青期纹枯病怎么防治")
    assert "小麦" in grams
    assert "纹枯" in grams
    assert "返青" in grams
    assert "防治" in grams
    # 停用词被过滤
    assert "怎么" not in grams


def test_extract_bigrams_dedup():
    grams = extract_bigrams("小麦小麦纹枯纹枯")
    assert grams.count("小麦") == 1
    assert grams.count("纹枯") == 1


def test_rrf_merges_dedups_and_ranks():
    a = [{"id": 1, "score": 0.9}, {"id": 2, "score": 0.8}]
    b = [{"id": 2, "score": 0.5}, {"id": 3, "score": 0.4}]
    merged = reciprocal_rank_fusion([a, b], k=60)
    ids = [h["id"] for h in merged]
    # 去重：3 个唯一 id
    assert len(ids) == 3
    # 双路命中的 id=2 融合分最高，排第一
    assert ids[0] == 2


def test_rrf_empty_lists():
    assert reciprocal_rank_fusion([], k=60) == []
    assert reciprocal_rank_fusion([[], []], k=60) == []
