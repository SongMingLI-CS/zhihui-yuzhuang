"""AI 能力状态与降级边界单元测试（离线、无数据库）。

覆盖阶段 F 验收点：
1. 生产缺少真实密钥时判定 UNAVAILABLE（不得伪装成真实 AI）；
2. 演示/非生产环境允许受控降级（MOCK）；
3. 已配置密钥判定 REAL；
4. capabilities 输出不含任何密钥材料；
5. Embedder 在 UNAVAILABLE 状态下显式抛错（而非返回伪随机向量）。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pytest  # noqa: E402

from app.capabilities import (  # noqa: E402
    MODE_MOCK,
    MODE_REAL,
    MODE_UNAVAILABLE,
    capabilities_payload,
    embedding_capability,
    llm_capability,
)
from app.config import Settings  # noqa: E402
from app.rag.embedder import Embedder, EmbeddingUnavailableError  # noqa: E402


def _settings(**overrides) -> Settings:
    """构造独立 Settings（不读本机 .env，避免环境干扰）。"""
    base = {
        "app_env": "dev",
        "demo_mode": False,
        "deepseek_api_key": "",
        "embedding_api_key": "",
    }
    base.update(overrides)
    return Settings(_env_file=None, **base)  # type: ignore[call-arg]


# ---------------------------------------------------------------- 能力判定

def test_non_production_without_key_allows_mock():
    cfg = _settings(app_env="dev")
    assert llm_capability(cfg).mode == MODE_MOCK
    assert embedding_capability(cfg).mode == MODE_MOCK
    assert cfg.mock_allowed is True


def test_production_without_key_is_unavailable():
    cfg = _settings(app_env="production", demo_mode=False)
    llm = llm_capability(cfg)
    embedding = embedding_capability(cfg)
    assert llm.mode == MODE_UNAVAILABLE
    assert embedding.mode == MODE_UNAVAILABLE
    assert llm.available is False and embedding.available is False
    assert "不可用" in llm.detail


def test_production_with_demo_mode_allows_mock():
    cfg = _settings(app_env="production", demo_mode=True)
    assert cfg.mock_allowed is True
    assert embedding_capability(cfg).mode == MODE_MOCK


def test_configured_keys_report_real():
    cfg = _settings(
        app_env="production",
        deepseek_api_key="sk-deepseek-example",
        embedding_api_key="sk-embedding-example",
    )
    assert llm_capability(cfg).mode == MODE_REAL
    embedding = embedding_capability(cfg)
    assert embedding.mode == MODE_REAL
    assert "text-embedding" in embedding.detail


def test_embedding_falls_back_to_deepseek_key():
    cfg = _settings(app_env="production", deepseek_api_key="sk-deepseek-example")
    assert embedding_capability(cfg).mode == MODE_REAL
    assert "回退" in embedding_capability(cfg).detail


# ---------------------------------------------------------------- 密钥不泄露

def test_capabilities_payload_never_contains_keys():
    secret = "sk-super-secret-value-should-never-appear"
    cfg = _settings(app_env="production", deepseek_api_key=secret, embedding_api_key=secret)
    dumped = json.dumps(capabilities_payload(cfg), ensure_ascii=False)
    assert secret not in dumped
    assert "sk-" not in dumped
    assert "apiKey" not in dumped and "api_key" not in dumped
    # 但应能看出“已配置”
    assert capabilities_payload(cfg)["llm"]["configured"] is True


# ---------------------------------------------------------------- Embedder 降级边界

def test_embedder_unavailable_in_production_without_key():
    cfg = _settings(app_env="production", demo_mode=False)
    embedder = Embedder(settings=cfg)
    assert embedder.is_available is False
    assert embedder.mode == MODE_UNAVAILABLE
    with pytest.raises(EmbeddingUnavailableError):
        embedder.embed_texts(["冬小麦发黄怎么办"])


def test_embedder_mock_is_deterministic_outside_production():
    cfg = _settings(app_env="dev")
    embedder = Embedder(settings=cfg)
    assert embedder.mode == MODE_MOCK

    def values(vec) -> list:
        # pgvector.Vector 支持 to_list()；兜底走 list()
        return list(vec.to_list()) if hasattr(vec, "to_list") else list(vec)

    first = values(embedder.embed_texts(["同一段文本"])[0])
    second = values(Embedder(settings=cfg).embed_texts(["同一段文本"])[0])
    assert first == second
    assert len(first) == cfg.embedding_dim


def test_embedder_force_mock_overrides_production():
    cfg = _settings(app_env="production", demo_mode=False)
    embedder = Embedder(settings=cfg, force_mock=True)
    assert embedder.mode == MODE_MOCK
    assert embedder.is_available is True
