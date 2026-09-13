"""RAG 评测集与门槛（阶段 F）。

目标：让「检索质量」可度量、可回归，而不是靠人工感觉。本模块只做**纯计算**
（不连数据库），因此可在任何环境离线跑单测；真实取数由 ``scripts/eval_rag.py`` 负责。

指标：
- ``recallAtK``：期望文档是否命中前 K 条检索结果（按用例平均）；
- ``citationPresenceRate``：应作答的用例中，返回引用（非空 citations）的比例；
- ``refusalAccuracy``：应拒答的用例（知识库无依据）中，确实给出“未检索到依据”兜底回答的比例；
- ``crossTenantLeakRate``：检索结果中出现了**非授权租户**文档的比例（必须为 0）。

门槛：低于 ``EvalThresholds`` 即判定不通过（脚本以非零退出码反映），
避免把“能跑通”当成“可用”。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence

# 默认门槛（可按环境调整；修改需同步 docs）
DEFAULT_RECALL_THRESHOLD = 0.80
DEFAULT_CITATION_THRESHOLD = 0.90
DEFAULT_REFUSAL_THRESHOLD = 0.90


@dataclass(frozen=True)
class EvalCase:
    """单条评测用例。"""

    query: str
    expected_doc_titles: List[str] = field(default_factory=list)
    expect_answer: bool = True
    tenant_id: str = "global"
    note: str = ""

    @staticmethod
    def from_dict(raw: Dict) -> "EvalCase":
        return EvalCase(
            query=str(raw["query"]),
            expected_doc_titles=list(raw.get("expectedDocTitles") or []),
            expect_answer=bool(raw.get("expectAnswer", True)),
            tenant_id=str(raw.get("tenantId") or "global"),
            note=str(raw.get("note") or ""),
        )


@dataclass(frozen=True)
class CaseResult:
    """单条用例的实际检索结果（由 scripts/eval_rag.py 采集）。"""

    case: EvalCase
    retrieved_doc_titles: List[str]
    has_citations: bool
    refused: bool
    unauthorized_doc_titles: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class EvalThresholds:
    """门槛阈值。"""

    recall_at_k: float = DEFAULT_RECALL_THRESHOLD
    citation_presence: float = DEFAULT_CITATION_THRESHOLD
    refusal_accuracy: float = DEFAULT_REFUSAL_THRESHOLD


def load_eval_set(path: str | Path) -> List[EvalCase]:
    """读取 JSONL 评测集（每行一个用例）。"""
    cases: List[EvalCase] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        cases.append(EvalCase.from_dict(json.loads(text)))
    return cases


def recall_at_k(results: Sequence[CaseResult], k: int) -> float:
    """期望文档命中率（仅统计「期望作答」且给出了期望文档的用例）。"""
    considered = [r for r in results if r.case.expect_answer and r.case.expected_doc_titles]
    if not considered:
        return 1.0
    hit = 0
    for r in considered:
        top = set(r.retrieved_doc_titles[:k])
        if top & set(r.case.expected_doc_titles):
            hit += 1
    return hit / len(considered)


def citation_presence_rate(results: Sequence[CaseResult]) -> float:
    """应作答用例中返回了引用的比例。"""
    considered = [r for r in results if r.case.expect_answer]
    if not considered:
        return 1.0
    return sum(1 for r in considered if r.has_citations) / len(considered)


def refusal_accuracy(results: Sequence[CaseResult]) -> float:
    """应拒答用例中正确拒答的比例（无依据时必须拒答，不得编造）。"""
    considered = [r for r in results if not r.case.expect_answer]
    if not considered:
        return 1.0
    return sum(1 for r in considered if r.refused) / len(considered)


def cross_tenant_leak_rate(results: Sequence[CaseResult]) -> float:
    """检索结果中出现非授权租户文档的比例（必须为 0）。"""
    if not results:
        return 0.0
    leaked = sum(1 for r in results if r.unauthorized_doc_titles)
    return leaked / len(results)


def evaluate(
    results: Sequence[CaseResult],
    *,
    k: int = 5,
    thresholds: Optional[EvalThresholds] = None,
) -> Dict[str, object]:
    """计算全部指标并给出是否通过门槛的结论。"""
    th = thresholds or EvalThresholds()
    metrics = {
        "caseCount": len(results),
        f"recallAt{k}": round(recall_at_k(results, k), 4),
        "citationPresenceRate": round(citation_presence_rate(results), 4),
        "refusalAccuracy": round(refusal_accuracy(results), 4),
        "crossTenantLeakRate": round(cross_tenant_leak_rate(results), 4),
    }
    failures: List[str] = []
    if metrics[f"recallAt{k}"] < th.recall_at_k:
        failures.append(f"recallAt{k}={metrics[f'recallAt{k}']} < {th.recall_at_k}")
    if metrics["citationPresenceRate"] < th.citation_presence:
        failures.append(
            f"citationPresenceRate={metrics['citationPresenceRate']} < {th.citation_presence}")
    if metrics["refusalAccuracy"] < th.refusal_accuracy:
        failures.append(
            f"refusalAccuracy={metrics['refusalAccuracy']} < {th.refusal_accuracy}")
    if metrics["crossTenantLeakRate"] > 0:
        failures.append(f"crossTenantLeakRate={metrics['crossTenantLeakRate']} > 0（存在跨租户泄漏）")

    return {
        "metrics": metrics,
        "thresholds": {
            "recallAtK": th.recall_at_k,
            "citationPresenceRate": th.citation_presence,
            "refusalAccuracy": th.refusal_accuracy,
            "crossTenantLeakRate": 0.0,
        },
        "passed": not failures,
        "failures": failures,
    }


def build_results(cases: Iterable[EvalCase], fetched: Dict[str, Dict]) -> List[CaseResult]:
    """把取数结果（按 query 索引）与用例合并为 :class:`CaseResult` 列表。"""
    results: List[CaseResult] = []
    for case in cases:
        payload = fetched.get(case.query) or {}
        results.append(
            CaseResult(
                case=case,
                retrieved_doc_titles=list(payload.get("docTitles") or []),
                has_citations=bool(payload.get("hasCitations")),
                refused=bool(payload.get("refused")),
                unauthorized_doc_titles=list(payload.get("unauthorizedDocTitles") or []),
            )
        )
    return results


__all__ = [
    "CaseResult",
    "DEFAULT_CITATION_THRESHOLD",
    "DEFAULT_RECALL_THRESHOLD",
    "DEFAULT_REFUSAL_THRESHOLD",
    "EvalCase",
    "EvalThresholds",
    "build_results",
    "citation_presence_rate",
    "cross_tenant_leak_rate",
    "evaluate",
    "load_eval_set",
    "recall_at_k",
    "refusal_accuracy",
]
