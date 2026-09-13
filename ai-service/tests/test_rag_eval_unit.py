"""RAG 评测逻辑与资料清单校验（离线、无数据库）。

覆盖阶段 F：
- 评测集可解析（JSONL 结构合法、必填字段齐全）；
- 指标计算正确（召回率/引用存在率/拒答正确率/跨租户泄漏率）；
- 门槛判定正确（任一指标不达标则 passed=false 且给出失败原因）；
- 资料清单 `data/raw_docs/sources.json` 结构合法：未核验资料必须显式标注。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.eval.rag_eval import (  # noqa: E402
    CaseResult,
    EvalCase,
    EvalThresholds,
    citation_presence_rate,
    cross_tenant_leak_rate,
    evaluate,
    load_eval_set,
    recall_at_k,
    refusal_accuracy,
)

EVAL_SET = PROJECT_ROOT / "data" / "eval" / "rag_eval_set.jsonl"
SOURCES = PROJECT_ROOT / "data" / "raw_docs" / "sources.json"


def _case(query: str, docs=None, expect_answer=True) -> EvalCase:
    return EvalCase(query=query, expected_doc_titles=docs or [], expect_answer=expect_answer)


def test_eval_set_is_parseable_and_well_formed():
    cases = load_eval_set(EVAL_SET)
    assert len(cases) >= 5, "评测集至少应包含 5 条用例"
    assert any(c.expect_answer for c in cases), "应包含需作答用例"
    assert any(not c.expect_answer for c in cases), "应包含应拒答用例（验证不编造）"
    for c in cases:
        assert c.query.strip(), "query 不能为空"
        if c.expect_answer and c.expected_doc_titles:
            assert all(t.strip() for t in c.expected_doc_titles)


def test_recall_and_citation_metrics():
    results = [
        CaseResult(_case("q1", ["doc-a"]), ["doc-a", "doc-b"], True, False),
        CaseResult(_case("q2", ["doc-c"]), ["doc-x", "doc-y"], False, False),
    ]
    assert recall_at_k(results, k=5) == 0.5
    assert citation_presence_rate(results) == 0.5


def test_refusal_accuracy_only_counts_expect_no_answer_cases():
    results = [
        CaseResult(_case("q1", ["doc-a"], True), ["doc-a"], True, False),
        CaseResult(_case("q2", [], False), [], False, True),   # 正确拒答
        CaseResult(_case("q3", [], False), ["doc-z"], True, False),  # 未拒答 → 编造风险
    ]
    assert refusal_accuracy(results) == 0.5


def test_cross_tenant_leak_is_detected():
    clean = [CaseResult(_case("q1", ["a"]), ["a"], True, False)]
    leaked = [CaseResult(_case("q1", ["a"]), ["a", "other-tenant-doc"], True, False,
                         unauthorized_doc_titles=["other-tenant-doc"])]
    assert cross_tenant_leak_rate(clean) == 0.0
    assert cross_tenant_leak_rate(leaked) == 1.0


def test_evaluate_passes_when_all_metrics_meet_thresholds():
    results = [
        CaseResult(_case("q1", ["doc-a"]), ["doc-a"], True, False),
        CaseResult(_case("q2", [], False), [], False, True),
    ]
    report = evaluate(results, k=5)
    assert report["passed"] is True
    assert report["failures"] == []
    assert report["metrics"]["recallAt5"] == 1.0
    assert report["metrics"]["crossTenantLeakRate"] == 0.0


def test_evaluate_fails_on_low_recall_or_leak():
    weak = [
        CaseResult(_case("q1", ["doc-a"]), ["doc-x"], True, False),
        CaseResult(_case("q2", ["doc-b"]), ["doc-y"], True, False),
    ]
    report = evaluate(weak, k=5, thresholds=EvalThresholds(recall_at_k=0.8))
    assert report["passed"] is False
    assert any("recallAt5" in f for f in report["failures"])

    leaked = [
        CaseResult(_case("q1", ["doc-a"]), ["doc-a"], True, False,
                   unauthorized_doc_titles=["cross-tenant"]),
    ]
    leak_report = evaluate(leaked, k=5)
    assert leak_report["passed"] is False
    assert any("跨租户" in f for f in leak_report["failures"])


def test_sources_manifest_is_valid_and_flags_unverified():
    assert SOURCES.is_file(), "必须存在资料清单 data/raw_docs/sources.json"
    manifest = json.loads(SOURCES.read_text(encoding="utf-8"))
    docs = manifest.get("documents")
    assert isinstance(docs, list) and docs, "documents 不能为空"

    raw_dir = PROJECT_ROOT / "data" / "raw_docs"
    present_files = {p.name for p in raw_dir.glob("*.txt")}
    listed_files = {d["file"] for d in docs}
    assert present_files <= listed_files, f"以下资料未登记来源: {present_files - listed_files}"

    for entry in docs:
        assert entry.get("file"), "file 必填"
        assert entry.get("title"), f"{entry['file']} 缺少 title"
        if entry.get("verified"):
            for key in ("publisher", "sourceUrl", "issuedDate"):
                assert entry.get(key), f"{entry['file']} 标记为已核验时必须提供 {key}"
        else:
            assert entry.get("note"), f"{entry['file']} 未核验时必须写明 note（待核验说明）"
