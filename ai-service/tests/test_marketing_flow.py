"""特产营销多 Agent 生成与合规质检全链路验证脚本（离线确定性强）。

覆盖任务验收点：
1. 服务级：POST /ai/v1/marketing/generate 的编排器输出完整
   ``thought_chain``（TrendAgent→CopywriterAgent→ComplianceAgent）、
   ``copies``（朋友圈/小红书/直播三套差异化文案）与 ``compliance`` 质检报告；
2. 合规拦截：故意传入含违规敏感词的产品名（如“天下第一治胃病神油”）时，
   ComplianceAgent 能准确捕捉并扣分（passed=False、score 低于阈值、
   risk_terms_detected 命中、revision_suggestions 给出整改建议），
   且对已生成的文案执行脱敏替换；
3. 规则引擎：直接断言「最强 / 顶级 / 包治百病」等典型违禁词可被命中；
4. HTTP 路由级：请求头携带 ``X-Tenant-Id``，返回 HTTP 200 且外层严格包裹
   ``code == "00000"``，data 内含完整 thought_chain / copies / compliance /
   review_status（固定 PENDING_HUMAN_REVIEW）；
5. 运行韧性：营销链路与数据库无耦合——不打开任何连接池即可离线跑通
   （DeepSeek 未配置时自动回退高质量 Mock 模板，接口绝不崩毁）。

运行方式（验收命令，二者均可）：
    python -m pytest tests/test_marketing_flow.py
    python tests/test_marketing_flow.py
全部断言通过时退出码为 0。

说明：为保证用例在任何环境（含 CI 配置了真实 DEEPSEEK_API_KEY）下都确定可复现，
本脚本在导入 app 前将 ``DEEPSEEK_API_KEY`` 置空并对 HTTP 路由单例强制离线，
仅验证“离线 Mock 模板链路 + 确定性规则质检”这条韧性主路径。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
from pathlib import Path
from typing import Dict, List

# 将 ai-service 根目录插入 sys.path，保证 `import app.*` 可解析
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

# 强制离线：必须在导入 app.main（其模块级调用 get_settings 并缓存）之前置空，
# 保证 HTTP 路由与编排器均走确定性 Mock 模板链路。
os.environ["DEEPSEEK_API_KEY"] = ""
os.environ.pop("EMBEDDING_API_KEY", None)

from app.agents import marketing_agents  # noqa: E402
from app.schemas.base import ApiResponse  # noqa: E402
from app.schemas.marketing import (  # noqa: E402
    REVIEW_STATUS_PENDING_HUMAN_REVIEW,
    MarketingGenerateRequest,
    MarketingGenerateResponse,
)
from app.services.marketing_service import MarketingOrchestrator  # noqa: E402

logger = logging.getLogger("test_marketing_flow")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

# ---------- 测试常量 ----------
TARGET_TENANT = "tenant_yuzhuang_001"
COMPLIANCE_PASS_THRESHOLD = marketing_agents.COMPLIANCE_PASS_THRESHOLD

# 良性验收载荷（对应验收 curl 用例）
BENIGN_PAYLOAD = {
    "product_name": "鹿邑试量于庄小磨香油",
    "selling_points": ["石磨低温慢磨", "严选当地芝麻", "滴滴纯香"],
    "target_audience": "注重食品健康的家庭主妇",
    "channel_preferences": ["MOMENTS", "RED_BOOK", "LIVESTREAM"],
}

# 违规载荷：产品名含极限词 + 疾病宣称（供合规拦截与脱敏断言）
VIOLATING_PAYLOAD = {
    "product_name": "天下第一治胃病神油",
    "selling_points": ["古法研磨", "无任何添加剂"],
    "channel_preferences": ["MOMENTS", "RED_BOOK", "LIVESTREAM"],
}

# 进程内状态：全流程只执行一次，pytest 多个用例复用结果
_STATE: Dict[str, object] = {"passed": None}

_EXPECTED_AGENT_SEQUENCE = ["TrendAgent", "CopywriterAgent", "ComplianceAgent"]
_EXPECTED_CHANNELS = {"MOMENTS", "RED_BOOK", "LIVESTREAM"}


# ---------------------------------------------------------------- 结构断言

def _assert_response_skeleton(
    resp: MarketingGenerateResponse,
    *,
    tenant: str,
    product_name: str,
    label: str,
) -> dict:
    """校验 MarketingGenerateResponse 结构契约：thought_chain / copies / compliance。"""
    data = resp.model_dump(mode="json")

    # ---- thought_chain：三节点按序 + 摘要非空 ----
    chain = data.get("thought_chain")
    assert isinstance(chain, list) and len(chain) >= 3, f"{label} thought_chain 缺失"
    agent_names = [node.get("agent_name") for node in chain[:3]]
    assert agent_names == _EXPECTED_AGENT_SEQUENCE, (
        f"{label} Agent 链路顺序不符: {agent_names}"
    )
    for node in chain:
        assert node.get("output_summary"), f"{label} Agent 摘要缺失: {node}"
    # 思考摘要应带出产品上下文
    all_summary = "\n".join(n["output_summary"] for n in chain)
    assert product_name in all_summary or "质检" in all_summary, (
        f"{label} 摘要未体现产品/质检信息"
    )

    # ---- copies：三套差异化文案字段完整 ----
    copies = data.get("copies")
    assert isinstance(copies, list) and copies, f"{label} copies 缺失"
    channels = set()
    contents = set()
    for item in copies:
        assert item.get("channel") in _EXPECTED_CHANNELS, f"{label} 非法渠道: {item}"
        channels.add(item["channel"])
        for field in ("title", "content", "call_to_action"):
            assert item.get(field), f"{label} {field} 缺失"
        contents.add(item["content"])
    assert channels == _EXPECTED_CHANNELS, f"{label} 渠道覆盖不全: {channels}"
    assert len(contents) == len(copies), f"{label} 各渠道文案未差异化"

    # ---- compliance：评分范围 + review_status 固定 ----
    compliance = data.get("compliance")
    assert isinstance(compliance, dict), f"{label} compliance 缺失"
    score = compliance.get("score")
    assert isinstance(score, int) and 0 <= score <= 100, f"{label} score 越界: {score}"
    assert isinstance(compliance.get("passed"), bool)
    assert isinstance(compliance.get("risk_terms_detected"), list)
    assert isinstance(compliance.get("revision_suggestions"), list)
    assert data.get("review_status") == REVIEW_STATUS_PENDING_HUMAN_REVIEW, (
        f"{label} review_status 应为 {REVIEW_STATUS_PENDING_HUMAN_REVIEW}"
    )

    # ---- 外层包裹 ----
    wrapped = ApiResponse.ok(data=resp).model_dump(mode="json")
    assert wrapped["code"] == "00000", f"{label} 外层 code 应为 00000"
    # data 载荷为 MarketingGenerateResponse 业务字段（无顶层 product_name），
    # 以 compliance 子载荷一致性验证外层与业务 payload 对齐。
    assert wrapped["data"]["compliance"] == compliance, f"{label} 外层与业务载荷不一致"
    return {"chain": len(chain), "copies": len(copies), "score": score}


def _concat_copy_text(item: dict) -> str:
    """拼接单套文案的可扫描文本。"""
    return "\n".join(
        [item.get("title", ""), item.get("content", ""), item.get("call_to_action", "")]
    )


# ---------------------------------------------------------------- 服务级断言

async def _service_level_checks() -> dict:
    """MarketingOrchestrator 编排器级断言（良性 + 违规，均强制离线）。"""
    summary: dict = {}
    orchestrator = MarketingOrchestrator(force_offline=True)

    # ---- 1) 良性载荷：三套文案 + 满分合规 ----
    resp_ok = await orchestrator.generate(
        MarketingGenerateRequest(**BENIGN_PAYLOAD), tenant_id=TARGET_TENANT
    )
    _assert_response_skeleton(
        resp_ok, tenant=TARGET_TENANT, product_name=BENIGN_PAYLOAD["product_name"], label="service_ok"
    )
    compliance_ok = resp_ok.compliance
    assert compliance_ok.passed is True, "良性载荷应通过合规质检"
    assert compliance_ok.score == 100, f"良性载荷合规评分应为 100，实际 {compliance_ok.score}"
    assert compliance_ok.risk_terms_detected == [], "良性载荷不应命中敏感词"
    # 良性模板文案本身也必须是干净的（防止模板带入违禁词造成误报）
    benign_scan = [_concat_copy_text(item.model_dump(mode="json")) for item in resp_ok.copies]
    benign_scan.append(BENIGN_PAYLOAD["product_name"])
    benign_scan.extend(BENIGN_PAYLOAD["selling_points"])
    assert marketing_agents.detect_risk_terms(benign_scan) == [], (
        "离线模板/良性载荷不应含任何违禁词"
    )
    summary["service_benign_passed"] = True

    # ---- 2) 违规载荷：ComplianceAgent 准确捕捉 + 扣分 + 脱敏 ----
    resp_bad = await orchestrator.generate(
        MarketingGenerateRequest(**VIOLATING_PAYLOAD), tenant_id=TARGET_TENANT
    )
    _assert_response_skeleton(
        resp_bad,
        tenant=TARGET_TENANT,
        product_name=VIOLATING_PAYLOAD["product_name"],
        label="service_bad",
    )
    compliance_bad = resp_bad.compliance
    assert compliance_bad.passed is False, "含违禁词应判不通过"
    assert compliance_bad.score < COMPLIANCE_PASS_THRESHOLD, (
        f"违禁词应扣分至 {COMPLIANCE_PASS_THRESHOLD} 以下，实际 {compliance_bad.score}"
    )
    risk = compliance_bad.risk_terms_detected
    assert "天下第一" in risk, f"应命中「天下第一」: {risk}"
    assert "治胃病" in risk, f"应命中「治胃病」: {risk}"
    assert compliance_bad.revision_suggestions, "违规时应给出整改建议"
    # 脱敏验证：返回文案不应再包含原始违禁表述
    for item in resp_bad.copies:
        text = _concat_copy_text(item.model_dump(mode="json"))
        assert "天下第一" not in text, "脱敏后不应残留「天下第一」"
        assert "治胃病" not in text, "脱敏后不应残留「治胃病」"
    summary["service_violation_caught"] = True
    summary["violation_score"] = compliance_bad.score

    # ---- 3) 规则引擎：规范示例词 最强 / 顶级 / 包治百病 ----
    rule_hits = marketing_agents.detect_risk_terms(
        ["本品最强、顶级，包治百病，谁用谁知道"]
    )
    for expected in ("最强", "顶级", "包治百病"):
        assert expected in rule_hits, f"规则引擎应命中「{expected}」: {rule_hits}"
    summary["rule_engine_ok"] = True

    # ---- 4) 空卖点/默认渠道韧性：不会崩毁且渠道覆盖全 ----
    resp_sparse = await orchestrator.generate(
        MarketingGenerateRequest(product_name="于庄手工粉条"), tenant_id=TARGET_TENANT
    )
    sparse = _assert_response_skeleton(
        resp_sparse, tenant=TARGET_TENANT, product_name="于庄手工粉条", label="service_sparse"
    )
    assert sparse["copies"] == 3, "缺省渠道应产出三套文案"
    summary["service_sparse_ok"] = True
    return summary


# ---------------------------------------------------------------- HTTP 路由级断言

async def _http_level_checks() -> dict:
    """HTTP 路由级断言：httpx ASGITransport 直连 ASGI 应用。

    营销链路与数据库无耦合，ASGITransport 不触发 lifespan，
    因此无需打开任何连接池即可离线验证。
    """
    from httpx import ASGITransport, AsyncClient  # noqa: PLC0415

    from app.api import marketing as marketing_api  # noqa: PLC0415
    from app.main import app  # noqa: PLC0415

    # 强制 HTTP 单例走离线模板，保证与 shell 环境是否存在真实 Key 无关
    marketing_api._service = MarketingOrchestrator(force_offline=True)

    summary: dict = {}
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        cases = [
            {"name": "benign", "payload": BENIGN_PAYLOAD, "expect_passed": True},
            {"name": "violating", "payload": VIOLATING_PAYLOAD, "expect_passed": False},
        ]
        for case in cases:
            resp = await client.post(
                "/ai/v1/marketing/generate",
                json=case["payload"],
                headers={"X-Tenant-Id": TARGET_TENANT},
            )
            assert resp.status_code == 200, f"http[{case['name']}] HTTP {resp.status_code}"
            body = resp.json()
            assert body["code"] == "00000", (
                f"http[{case['name']}] 外层 code 应为 00000，实际 {body['code']}"
            )
            data = body.get("data")
            assert isinstance(data, dict), f"http[{case['name']}] data 缺失"
            assert isinstance(data.get("thought_chain"), list) and len(data["thought_chain"]) >= 3
            copies = data.get("copies")
            assert isinstance(copies, list) and len(copies) == 3
            compliance = data.get("compliance")
            assert isinstance(compliance, dict)
            assert compliance["passed"] is case["expect_passed"], (
                f"http[{case['name']}] passed 不符预期"
            )
            assert data.get("review_status") == REVIEW_STATUS_PENDING_HUMAN_REVIEW
            logger.info(
                "HTTP[%s] 200 code=00000 passed=%s score=%d copies=%d",
                case["name"],
                compliance["passed"],
                compliance["score"],
                len(copies),
            )
            summary[f"http_{case['name']}"] = compliance["score"]
    return summary


# ---------------------------------------------------------------- 统一入口

async def _run_marketing_suite() -> dict:
    """单事件循环编排：服务级断言 + HTTP 级断言（无数据库依赖）。"""
    summary = await _service_level_checks()
    summary.update(await _http_level_checks())
    return summary


def run_checks() -> dict:
    """执行全流程（进程内幂等），所有断言通过则返回汇总。"""
    if _STATE["passed"] is not None:
        return _STATE["passed"]  # type: ignore[return-value]

    print("=" * 64)
    print("特产营销多 Agent 链路验证启动（离线 Mock 模板 + 规则质检）")
    print("=" * 64)

    summary = asyncio.run(_run_marketing_suite())

    _STATE["passed"] = summary
    print("=" * 64)
    print(
        f"营销链路断言通过 ✔  良性通过={summary.get('service_benign_passed')} "
        f"违规命中={summary.get('service_violation_caught')} "
        f"规则引擎={summary.get('rule_engine_ok')} "
        f"HTTP良性评分={summary.get('http_benign')} "
        f"HTTP违规评分={summary.get('http_violating')}"
    )
    print("=" * 64)
    return summary


def _main() -> int:
    """独立运行入口（python tests/test_marketing_flow.py）。"""
    try:
        run_checks()
    except AssertionError as exc:
        print(f"\n[FAIL] 断言未通过: {exc}")
        return 1
    except Exception as exc:  # noqa: BLE001
        logger.exception("全链路执行异常")
        print(f"\n[ERROR] 全链路执行异常: {exc}")
        return 2
    print("\n[PASS] 特产营销多 Agent 生成与合规质检验证全部通过")
    return 0


# ---------------------------------------------------------------- pytest 用例

def test_marketing_flow_end_to_end() -> None:
    """服务级 + HTTP 级全链路断言（可同时被 pytest 收集）。"""
    run_checks()


def test_compliance_catches_banned_terms_and_deducts() -> None:
    """故意包含“天下第一治胃病神油”等违禁词时能准确捕捉并扣分。"""
    summary = run_checks()
    assert summary.get("service_violation_caught") is True
    assert int(summary.get("violation_score", 100)) < COMPLIANCE_PASS_THRESHOLD


def test_api_wrapper_code_00000() -> None:
    """HTTP 外层严格包裹 code=00000 且 data 结构完整。"""
    summary = run_checks()
    assert "http_benign" in summary
    assert "http_violating" in summary


def test_rule_engine_hits_spec_examples() -> None:
    """规则引擎命中规范示例词：最强 / 顶级 / 包治百病。"""
    summary = run_checks()
    assert summary.get("rule_engine_ok") is True


if __name__ == "__main__":
    sys.exit(_main())
