"""AI 能力状态汇总（不泄露任何密钥）。

用途（阶段 F）：
- ``GET /ai/v1/capabilities``：登录后可查看“LLM / Embedding 是否已配置、当前是真实还是降级”，
  只返回布尔、模式与模型名，**绝不回显密钥**；
- ``GET /ai/v1/readyz``：readiness 依据本模块判定真实能力是否就绪，生产缺少真实能力即判不通过。

模式约定：
- ``REAL``：已配置真实密钥，走线上模型；
- ``MOCK``：未配置密钥但允许降级（``DEMO_MODE=true`` 或非生产），返回确定性伪随机向量/离线模板；
- ``UNAVAILABLE``：生产环境且未配置真实密钥 —— 明确不可用，不得伪装成真实 AI。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict

from app.config import Settings, get_settings

MODE_REAL = "REAL"
MODE_MOCK = "MOCK"
MODE_UNAVAILABLE = "UNAVAILABLE"


@dataclass(frozen=True)
class CapabilityStatus:
    """单项能力状态。"""

    name: str
    configured: bool
    available: bool
    mode: str
    detail: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def llm_capability(settings: Settings | None = None) -> CapabilityStatus:
    """LLM（DeepSeek 对话/生成）能力状态。"""
    cfg = settings or get_settings()
    if cfg.deepseek_enabled:
        return CapabilityStatus(
            name="llm",
            configured=True,
            available=True,
            mode=MODE_REAL,
            detail=f"DeepSeek 已配置（base_url={cfg.deepseek_base_url}）",
        )
    if cfg.mock_allowed:
        return CapabilityStatus(
            name="llm",
            configured=False,
            available=True,
            mode=MODE_MOCK,
            detail="未配置 DEEPSEEK_API_KEY：当前走离线模板（演示/非生产降级）",
        )
    return CapabilityStatus(
        name="llm",
        configured=False,
        available=False,
        mode=MODE_UNAVAILABLE,
        detail="生产环境未配置 DEEPSEEK_API_KEY：生成类能力不可用",
    )


def embedding_capability(settings: Settings | None = None) -> CapabilityStatus:
    """Embedding（向量化）能力状态。"""
    cfg = settings or get_settings()
    if cfg.embedding_configured:
        source = "EMBEDDING_API_KEY" if cfg.embedding_api_key else "DEEPSEEK_API_KEY(回退)"
        return CapabilityStatus(
            name="embedding",
            configured=True,
            available=True,
            mode=MODE_REAL,
            detail=f"Embedding 已配置（{source}，model={cfg.embedding_model}, dim={cfg.embedding_dim}）",
        )
    if cfg.mock_allowed:
        return CapabilityStatus(
            name="embedding",
            configured=False,
            available=True,
            mode=MODE_MOCK,
            detail="未配置 Embedding 密钥：当前使用确定性伪随机向量（仅演示/非生产）",
        )
    return CapabilityStatus(
        name="embedding",
        configured=False,
        available=False,
        mode=MODE_UNAVAILABLE,
        detail="生产环境未配置 Embedding 密钥：向量检索不可用",
    )


def capabilities_payload(settings: Settings | None = None) -> Dict[str, Any]:
    """汇总能力状态（供 /capabilities 与 readiness 复用）。"""
    cfg = settings or get_settings()
    llm = llm_capability(cfg)
    embedding = embedding_capability(cfg)
    return {
        "appEnv": cfg.app_env,
        "demoMode": cfg.demo_mode,
        "mockAllowed": cfg.mock_allowed,
        "llm": llm.to_dict(),
        "embedding": embedding.to_dict(),
        "models": {
            "embeddingModel": cfg.embedding_model,
            "embeddingDim": cfg.embedding_dim,
            "llmBaseUrl": cfg.deepseek_base_url,
        },
    }


__all__ = [
    "CapabilityStatus",
    "MODE_MOCK",
    "MODE_REAL",
    "MODE_UNAVAILABLE",
    "capabilities_payload",
    "embedding_capability",
    "llm_capability",
]
