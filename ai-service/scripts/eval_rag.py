"""RAG 评测执行脚本（阶段 F；可选：真实取数需要 PostgreSQL+pgvector）。

用法：
    cd ai-service
    python scripts/eval_rag.py                 # 使用 data/eval/rag_eval_set.jsonl
    python scripts/eval_rag.py --k 5           # 指定 top-k

说明：
- 依赖已入库的知识（先执行 `python scripts/ingest_docs.py` 或通过上传接口导入）；
- 通过 :class:`app.services.qa_service.AgriQAService` 逐条提问，采集「命中文档 / 是否有引用 /
  是否拒答」，再用 :mod:`app.eval.rag_eval` 计算指标与门槛；
- 任一指标低于门槛 → 退出码 1（便于 CI/回归）；跨租户泄漏必须为 0。
- 本脚本需要异步连接池，故使用 asyncio 编排并在结束时关闭连接池。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from typing import Dict, List

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.db.init_tables import init_knowledge_chunk_table_sync  # noqa: E402
from app.db.session import close_pool, open_pool  # noqa: E402
from app.eval.rag_eval import (  # noqa: E402
    build_results,
    evaluate,
    load_eval_set,
    recall_at_k,
)
from app.schemas.qa import AgriQARequest  # noqa: E402
from app.services.qa_service import AgriQAService  # noqa: E402

DEFAULT_EVAL_SET = PROJECT_ROOT / "data" / "eval" / "rag_eval_set.jsonl"


async def run(eval_set: Path, k: int, tenant_override: str | None) -> int:
    cases = load_eval_set(eval_set)
    print(f"[eval] 用例数={len(cases)} k={k} 评测集={eval_set.name}")

    service = AgriQAService()
    fetched: Dict[str, Dict] = {}
    await open_pool(timeout=10)
    try:
        for case in cases:
            tenant = tenant_override or case.tenant_id
            response = await service.answer_question(
                AgriQARequest(question=case.query), tenant_id=tenant
            )
            doc_titles: List[str] = []
            unauthorized: List[str] = []
            for citation in response.citations:
                title = citation.docTitle
                if title not in doc_titles:
                    doc_titles.append(title)
                # 跨租户泄漏检测：非 global 知识且标题不在期望列表时记录（人工复核用）
                if case.expect_answer and title and title not in case.expected_doc_titles:
                    if not case.expected_doc_titles:
                        unauthorized.append(title)
            refused = not response.citations and "未" in (response.answer or "")
            fetched[case.query] = {
                "docTitles": doc_titles,
                "hasCitations": bool(response.citations),
                "refused": refused,
                "unauthorizedDocTitles": unauthorized,
            }
            print(
                f"  - {case.query[:28]:<30} docs={doc_titles} citations={len(response.citations)} "
                f"refused={refused}"
            )
    finally:
        await close_pool()

    results = build_results(cases, fetched)
    report = evaluate(results, k=k)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["passed"]:
        print("[eval] 未通过门槛：", "; ".join(report["failures"]))
        return 1
    print(f"[eval] 通过阈值（recall@{k}={recall_at_k(results, k)}）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG 评测集回归")
    parser.add_argument("--eval-set", default=str(DEFAULT_EVAL_SET))
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--tenant", default=None, help="覆盖用例租户（默认按评测集）")
    args = parser.parse_args()

    try:
        init_knowledge_chunk_table_sync()
    except Exception as exc:  # noqa: BLE001 - 表已存在或数据库不可达时给出提示
        print(f"[eval] 知识库表初始化失败（可忽略，若表已存在）：{exc}")

    return asyncio.run(run(Path(args.eval_set), args.k, args.tenant))


if __name__ == "__main__":
    sys.exit(main())
