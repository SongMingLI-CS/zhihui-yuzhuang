"""农技问答 RAG 全链路端到端验证脚本。

覆盖任务验收点：
1. 使用入库时存在的文本（知识库切片原文）作为 query 调用 :class:`AgriQAService`，
   断言检索能够召回已入库的 ``yuzhuang_wheat_guide`` 切片（doc_title 与 score 校验）；
2. 验证返回 JSON 结构完全符合 docs/api-spec.yaml：
   - 外层 ``ApiResponse.code == "00000"``；
   - ``data`` 内含 ``answer`` / ``citations`` / ``disclaimer``；
   - ``citations[]`` 元素含 ``docTitle`` / ``pageNumber`` / ``chunkText`` / ``similarityScore``；
3. 离线韧性：未配置 Embedding/DeepSeek 密钥时（Mock 模式 + LLM 不可用），
   系统不会崩溃——自然语言问题命中熔断兜底回答（citations 为空、不请求 LLM）；
4. 租户边界：无关租户检索不到私有租户 ``tenant_yuzhuang_001`` 的知识；
5. HTTP 路由：``POST /ai/v1/qa/ask`` 返回 HTTP 200 + 标准包裹
   （通过 FastAPI TestClient 验证 Header 读取与路由挂载）。

关于离线（Mock）检索的说明：未配置 Embedding 密钥时 :class:`Embedder` 进入 Mock 模式，
其向量是对整段文本的确定性哈希（无语义），因此只有“以入库原文整片作为 query”才能达到
余弦 ≈ 1 的召回。本脚本的召回断言因此采用**入库时存在的完整切片文本**作为 query，
从而在离线（Mock）与在线（真实 Embedding）两种环境下均能稳定通过；
同时以自然语言问句走通熔断/降级路径（在线时可正常召回）。

运行方式（验收命令，二者均可）：
    python -m pytest tests/test_qa_flow.py
    python tests/test_qa_flow.py
全部断言通过时退出码为 0。
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

# 将 ai-service 根目录插入 sys.path，保证 `import app.*` 可解析
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import get_settings  # noqa: E402
from app.db.init_tables import init_knowledge_chunk_table_sync  # noqa: E402
from app.db.session import (  # noqa: E402
    close_pool,
    close_sync_pool,
    connection,
    open_pool,
    open_sync_pool,
    sync_pool,
)
from app.rag.embedder import Embedder  # noqa: E402
from app.rag.keyword_retriever import KeywordRetriever  # noqa: E402
from app.rag.retriever import KnowledgeRetriever  # noqa: E402
from app.rag.splitter import DocumentSplitter  # noqa: E402
from app.schemas.base import ApiResponse  # noqa: E402
from app.schemas.qa import AgriQARequest, AgriQAResponse  # noqa: E402
from app.services.qa_service import (  # noqa: E402
    DEGRADE_ANSWER,
    FALLBACK_ANSWER,
    AgriQAService,
)

logger = logging.getLogger("test_qa_flow")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

# ---------- 测试常量 ----------
SAMPLE_FILE = PROJECT_ROOT / "data" / "raw_docs" / "yuzhuang_wheat_guide.txt"
TARGET_TENANT = "tenant_yuzhuang_001"  # 与 ingest_docs 演示入库租户一致
TARGET_DOC = "yuzhuang_wheat_guide"    # 入库时 doc_title = 文件名（去扩展名）
TARGET_CATEGORY = "DISEASE_PEST"
# 自然语言问句（在线真实 Embedding 下可召回；离线 Mock 下命中熔断兜底）
NATURAL_QUESTION = "小麦返青期纹枯病怎么防治？打什么药？"
NATURAL_HEADING = "小麦返青拔节期主要病虫害防治规范"

_INSERT_SQL = """
INSERT INTO t_knowledge_chunk
    (tenant_id, doc_title, page_number, category, chunk_text, embedding, metadata)
VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb)
"""

# 进程内状态：全流程只执行一次，pytest 多个用例复用结果
_STATE: Dict[str, object] = {"samples": None, "passed": None}


# ---------------------------------------------------------------- 知识库自给（幂等）

def _fetch_samples(tenant_id: str, doc_title: str) -> List[dict]:
    """按 (tenant, doc_title) 读取已入库切片（含 id/chunk_text/page_number/category）。"""
    with sync_pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, tenant_id, doc_title, page_number, category, chunk_text
                FROM t_knowledge_chunk
                WHERE tenant_id = %s AND doc_title = %s
                ORDER BY id
                """,
                (tenant_id, doc_title),
            )
            return list(cur.fetchall())


def _delete_samples(tenant_id: str, doc_title: str) -> None:
    """删除指定 (tenant, doc_title) 的切片（用于嵌入模式不一致时以当前模式重灌）。"""
    with sync_pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM t_knowledge_chunk WHERE tenant_id = %s AND doc_title = %s",
                (tenant_id, doc_title),
            )
        conn.commit()


def _ingest_sample(tenant_id: str, doc_title: str, category: str) -> None:
    """用与生产一致的 splitter + embedder 把示例文档切片入库（当前嵌入模式）。"""
    splitter = DocumentSplitter()
    embedder = Embedder()
    chunks = splitter.split_file(SAMPLE_FILE, doc_title=doc_title)
    vectors = embedder.embed_texts([c.chunk_text for c in chunks])
    if len(chunks) != len(vectors):
        raise RuntimeError("切片数与向量数不一致，无法入库")
    rows = []
    for chunk, vec in zip(chunks, vectors):
        metadata = json.dumps(
            {
                "source": SAMPLE_FILE.name,
                "doc_path": str(SAMPLE_FILE),
                "chunk_index": chunk.chunk_index,
                "splitter": "yuzhuang-document-splitter",
                "test_seed": True,
            },
            ensure_ascii=False,
        )
        rows.append(
            (
                tenant_id,
                chunk.doc_title or doc_title,
                chunk.page_number,
                category,
                chunk.chunk_text,
                vec,
                metadata,
            )
        )
    with sync_pool.connection() as conn:
        with conn.cursor() as cur:
            cur.executemany(_INSERT_SQL, rows)
        conn.commit()
    logger.info("已按当前模式入库 %s → %d 片（tenant=%s）", doc_title, len(rows), tenant_id)


def _embedding_mode_consistent(row: dict) -> bool:
    """当前模式下，用切片原文重新向量化，与库中已存向量对比是否同源（余弦≈1）。

    用于识别“库由真实 Embedding 灌入、当前跑 Mock”这类模式不一致场景；
    不一致时重灌，保证测试与运行时 Embedder 同源、检索可复现。
    """
    embedder = Embedder()
    vec = embedder.embed_texts([row["chunk_text"]])[0]
    with sync_pool.connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 - (embedding <=> %s::vector) AS score FROM t_knowledge_chunk WHERE id = %s",
                (vec, row["id"]),
            )
            result = cur.fetchone()
    return bool(result) and float(result["score"] or 0.0) >= 0.5


def _ensure_samples() -> List[dict]:
    """确保示例知识就绪并处于当前嵌入模式，返回已入库切片列表。"""
    if _STATE["samples"] is not None:
        return _STATE["samples"]  # type: ignore[return-value]

    init_knowledge_chunk_table_sync()
    open_sync_pool()
    try:
        samples = _fetch_samples(TARGET_TENANT, TARGET_DOC)
        if not samples:
            logger.info("知识库为空，先入库示例文档 %s", SAMPLE_FILE.name)
            _ingest_sample(TARGET_TENANT, TARGET_DOC, TARGET_CATEGORY)
            samples = _fetch_samples(TARGET_TENANT, TARGET_DOC)

        # 模式一致性自检：不一致则删除并以当前模式重灌（保证离线/在线均可复现召回）
        if samples and not _embedding_mode_consistent(samples[0]):
            logger.warning("检测到库中向量与当前 Embedder 模式不一致，重灌示例文档...")
            _delete_samples(TARGET_TENANT, TARGET_DOC)
            _ingest_sample(TARGET_TENANT, TARGET_DOC, TARGET_CATEGORY)
            samples = _fetch_samples(TARGET_TENANT, TARGET_DOC)

        if not samples:
            raise RuntimeError("示例知识初始化失败：t_knowledge_chunk 无可检索切片")
        _STATE["samples"] = samples
        return samples
    finally:
        close_sync_pool()


# ---------------------------------------------------------------- 结构与契约校验

def _assert_valid_response(resp: AgriQAResponse) -> dict:
    """校验 AgriQAResponse 及外层 ApiResponse 完全符合 OpenAPI 契约。"""
    data = resp.model_dump(mode="json")
    assert isinstance(data.get("answer"), str) and data["answer"].strip(), "answer 缺失"
    assert "disclaimer" in data, "disclaimer 字段缺失"
    citations = data.get("citations")
    assert isinstance(citations, list), "citations 必须是数组"
    for cit in citations:
        assert isinstance(cit.get("docTitle"), str) and cit["docTitle"], "docTitle 缺失"
        assert "pageNumber" in cit, "pageNumber 字段缺失"
        assert isinstance(cit.get("chunkText"), str) and cit["chunkText"], "chunkText 缺失"
        score = cit.get("similarityScore")
        assert isinstance(score, (int, float)), "similarityScore 缺失"
        assert 0.0 <= float(score) <= 1.0, f"similarityScore 越界: {score}"

    wrapped = ApiResponse.ok(data=resp).model_dump(mode="json")
    assert wrapped["code"] == "00000", f"外层 code 应为 00000，实际 {wrapped['code']}"
    assert wrapped["message"], "外层 message 缺失"
    assert wrapped["data"]["answer"] == data["answer"], "外层 data 与业务载荷不一致"
    return wrapped


# ---------------------------------------------------------------- 服务级（离线/在线）断言

async def _service_level_checks(samples: List[dict]) -> dict:
    """AgriQAService 全链路断言：召回 + 熔断兜底 + 租户边界 + JSON 契约。

    前置条件：异步连接池已由 :func:`_run_qa_suite` 统一打开，本函数不开关连接池
    （psycopg 异步池关闭后不可复用，故池生命周期收敛到编排函数）。
    """
    summary: dict = {}
    retriever = KnowledgeRetriever()   # 自动按配置进入 Mock / 真实模式
    service = AgriQAService(retriever=retriever)
    embedder = retriever.embedder
    llm_available = bool(getattr(service.llm, "available", False))
    logger.info(
        "运行模式：embedding=%s llm=%s",
        "Mock" if embedder.is_mock else "Real",
        "available" if llm_available else "unavailable",
    )

    # ---- 1) 检索召回断言：query = 入库时存在的完整切片原文 ----
    # 优先选覆盖“防治规范/纹枯病/药剂”语义、且长度<=500 的切片（HTTP/请求体
    # question max_length=500），否则退而求其次选任意 <=500 切片，最后兜底首片。
    recall_chunk = next(
        (
            c
            for c in samples
            if any(k in c["chunk_text"] for k in ("纹枯病", "防治", "药剂", "返青"))
            and 5 <= len(c["chunk_text"]) <= 500
        ),
        next((c for c in samples if 5 <= len(c["chunk_text"]) <= 500), samples[0]),
    )
    query_exact: str = recall_chunk["chunk_text"]
    logger.info("精确召回 query 长度=%d 字", len(query_exact))

    started = time.perf_counter()
    hits = await retriever.search(
        query_text=query_exact,
        tenant_id=TARGET_TENANT,
        category=TARGET_CATEGORY,
        top_k=3,
        min_score=0.65,
    )
    latency_ms = (time.perf_counter() - started) * 1000
    summary["retrieval_latency_ms"] = round(latency_ms, 2)

    assert hits, "检索未召回任何 score>=0.65 的切片（yuzhuang_wheat_guide 应可命中）"
    top = hits[0]
    assert top["doc_title"] == TARGET_DOC, f"召回文档不符: {top['doc_title']}"
    assert top["score"] >= 0.65, f"top 命中 score={top['score']} < 0.65"
    assert 0.0 <= top["score"] <= 1.0, "score 需裁剪在 [0,1]"
    logger.info("召回命中 doc=%s score=%.4f 检索耗时=%.2fms", top["doc_title"], top["score"], latency_ms)

    # ---- 1b) 关键词召回断言：自然语言问句应命中包含"纹枯/返青/小麦"的切片 ----
    kw_retriever = KeywordRetriever()
    kw_hits = await kw_retriever.search(
        query_text=NATURAL_QUESTION,
        tenant_id=TARGET_TENANT,
        category=TARGET_CATEGORY,
        top_k=3,
        min_score=0.0,
    )
    assert kw_hits, "关键词召回应命中包含关键词的切片"
    assert all(h["doc_title"] == TARGET_DOC for h in kw_hits), (
        f"关键词召回应命中目标文档，实际 {[h['doc_title'] for h in kw_hits]}"
    )
    summary["keyword_recalled"] = len(kw_hits)

    # ---- 2) 服务级整片 query：应携带非空 citations ----
    resp_exact = await service.answer_question(
        AgriQARequest(question=query_exact, category=TARGET_CATEGORY),
        tenant_id=TARGET_TENANT,
    )
    assert resp_exact.citations, "以入库原文查询应召回引用 citations"
    assert resp_exact.citations[0].docTitle == TARGET_DOC
    assert resp_exact.answer.strip(), "answer 不应为空（离线为降级提示/在线为生成回答）"
    _assert_valid_response(resp_exact)
    summary["exact_recalled"] = len(resp_exact.citations)

    # ---- 3) 自然语言问句：模式无关，不应崩溃且 JSON 合规 ----
    resp_nat = await service.answer_question(
        AgriQARequest(question=NATURAL_QUESTION, category=TARGET_CATEGORY),
        tenant_id=TARGET_TENANT,
    )
    _assert_valid_response(resp_nat)
    if embedder.is_mock:
        # 离线 Mock（向量无语义，但关键词召回可命中）→ 走降级（LLM 不可用），保留 citations
        assert resp_nat.citations, "Mock 下自然语言应通过关键词召回命中依据"
        assert resp_nat.answer == DEGRADE_ANSWER, "Mock 下自然语言应关键词召回后降级"
    else:
        # 在线真实 Embedding：自然语言问句应召回
        assert resp_nat.citations, "真实 Embedding 下自然语言问句应召回依据"
    summary["natural_ok"] = True

    # ---- 4) 标题式问句（task 示例）走通全链路 ----
    resp_head = await service.answer_question(
        AgriQARequest(question=NATURAL_HEADING, category=TARGET_CATEGORY),
        tenant_id=TARGET_TENANT,
    )
    _assert_valid_response(resp_head)
    summary["heading_ok"] = True

    # ---- 5) 租户边界：无关租户查不到私有租户 tenant_yuzhuang_001 的知识 ----
    resp_foreign = await service.answer_question(
        AgriQARequest(question=query_exact, category=TARGET_CATEGORY),
        tenant_id="tenant_unrelated_000",
    )
    foreign_cit = resp_foreign.citations
    assert all(c.docTitle != TARGET_DOC for c in foreign_cit), (
        "无关租户不应检索到 tenant_yuzhuang_001 的私有知识"
    )
    logger.info(
        "租户隔离校验通过：foreign 租户命中 %d 条（应不含 %s）",
        len(foreign_cit),
        TARGET_DOC,
    )
    summary["tenant_isolated"] = True
    return summary


# ---------------------------------------------------------------- HTTP 路由级断言

async def _http_level_checks(samples: List[dict]) -> dict:
    """HTTP 路由级断言：httpx ASGITransport 直连 ASGI 应用，POST /ai/v1/qa/ask。

    与 _service_level_checks 同处一个事件循环（由 :func:`_run_qa_suite` 编排）：
    请求经 app 路由 + 中间件 + X-Tenant-Id Header 解析与响应模型序列化。
    不使用 TestClient，避免其独立事件循环与 psycopg 异步连接池冲突。
    """
    from httpx import ASGITransport, AsyncClient  # noqa: PLC0415

    from app.main import app  # noqa: PLC0415

    summary: dict = {}
    embedder = Embedder()  # 判定当前嵌入模式（与 app 内构建的 Embedder 同源）

    # 优先选一条长度<=500 的入库原文作为精确召回 query（HTTP 入参 max_length=500）
    short_chunk = next((c for c in samples if 5 <= len(c["chunk_text"]) <= 500), None)
    payload_cases: List[dict] = []
    if short_chunk is not None:
        payload_cases.append(
            {
                "name": "exact_stored_text",
                "body": {"question": short_chunk["chunk_text"], "category": TARGET_CATEGORY},
                "expect_citations": True,
                "expect_doc": TARGET_DOC,
            }
        )
    payload_cases.append(
        {
            "name": "natural_question",
            "body": {"question": NATURAL_QUESTION, "category": TARGET_CATEGORY},
            "expect_citations": not embedder.is_mock,
            "expect_doc": TARGET_DOC if not embedder.is_mock else None,
        }
    )

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        for case in payload_cases:
            resp = await client.post(
                "/ai/v1/qa/ask",
                json=case["body"],
                headers={"X-Tenant-Id": TARGET_TENANT},
            )
            assert resp.status_code == 200, f"{case['name']} HTTP {resp.status_code}"
            body = resp.json()
            assert body["code"] == "00000", f"{case['name']} code={body['code']}"
            data = body.get("data")
            assert isinstance(data, dict), f"{case['name']} data 缺失"
            assert data.get("answer"), f"{case['name']} answer 缺失"
            citations = data.get("citations")
            assert isinstance(citations, list), f"{case['name']} citations 必须是数组"
            # 每条引用字段完整
            for cit in citations:
                assert cit.get("docTitle"), f"{case['name']} docTitle 缺失"
                assert cit.get("chunkText"), f"{case['name']} chunkText 缺失"
                assert 0.0 <= float(cit.get("similarityScore", -1)) <= 1.0
            if case["expect_citations"]:
                assert citations, f"{case['name']} 应返回非空 citations"
                assert citations[0]["docTitle"] == case["expect_doc"], (
                    f"{case['name']} 首条引用 docTitle={citations[0]['docTitle']}"
                )
            logger.info(
                "HTTP[%s] 200 返回：answer=%d字 citations=%d",
                case["name"],
                len(data["answer"]),
                len(citations),
            )
            summary[f"http_{case['name']}"] = len(citations)
    return summary


# ---------------------------------------------------------------- 统一入口

async def _run_qa_suite(samples: List[dict]) -> dict:
    """单事件循环编排：打开连接池 → 服务级断言 → HTTP 级断言 → 关闭连接池。

    psycopg 异步连接池关闭后不可再次打开，且其连接绑定创建时的事件循环；
    因此服务级与 HTTP 级（httpx ASGITransport，同循环）必须共用同一池生命周期，
    全部结束后再统一关闭。
    """
    await open_pool(timeout=10)
    try:
        summary = await _service_level_checks(samples)
        summary.update(await _http_level_checks(samples))
        return summary
    finally:
        await close_pool()


def run_checks() -> dict:
    """执行端到端全流程（进程内幂等），所有断言通过则返回汇总。"""
    if _STATE["passed"] is not None:
        return _STATE["passed"]  # type: ignore[return-value]

    settings = get_settings()
    print("=" * 64)
    print(
        f"QA RAG 端到端验证启动 db={settings.database_url}\n"
        f"  DEEPSEEK_API_KEY={'已配置' if settings.deepseek_enabled else '未配置(走降级)'}\n"
        f"  Embedding={'Real' if settings.embedding_enabled else 'Mock(离线)'}"
    )
    print("=" * 64)

    samples = _ensure_samples()
    summary = asyncio.run(_run_qa_suite(samples))

    _STATE["passed"] = summary
    print("=" * 64)
    print(
        f"QA 全链路断言通过 ✔  召回切片={summary.get('exact_recalled', 0)} "
        f"检索耗时={summary.get('retrieval_latency_ms', 'N/A')}ms "
        f"HTTP精确召回={summary.get('http_exact_stored_text', 0)} 条"
    )
    print("=" * 64)
    return summary


def _main() -> int:
    """独立运行入口（python tests/test_qa_flow.py）。"""
    try:
        run_checks()
    except AssertionError as exc:
        print(f"\n[FAIL] 断言未通过: {exc}")
        return 1
    except Exception as exc:  # noqa: BLE001
        logger.exception("全链路执行异常")
        print(f"\n[ERROR] 全链路执行异常: {exc}")
        return 2
    print("\n[PASS] 农技问答 RAG 端到端验证全部通过")
    return 0


# ---------------------------------------------------------------- pytest 用例

def test_qa_flow_end_to_end() -> None:
    """服务级 + HTTP 级全链路断言（可同时被 pytest 收集）。"""
    run_checks()


def test_retriever_recalls_yuzhuang_wheat_guide() -> None:
    """检索器能召回已入库的 yuzhuang_wheat_guide 切片（min_score 阈值校验）。"""
    summary = run_checks()
    assert summary.get("exact_recalled", 0) > 0
    assert summary.get("retrieval_latency_ms", 0.0) < 5000.0  # 宽松性能护栏


def test_api_payload_matches_openapi() -> None:
    """返回 JSON 符合 OpenAPI：外层 code=00000，data.citations 数组字段完整。"""
    summary = run_checks()
    # run_checks 内部已对结构做严格断言；此处再抽查关键汇总
    assert "http_exact_stored_text" in summary


if __name__ == "__main__":
    sys.exit(_main())
