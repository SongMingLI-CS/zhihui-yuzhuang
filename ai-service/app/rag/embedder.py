"""Embedder（文本向量化客户端）。

安全/降级约定（阶段 F）：
- 已配置有效密钥 → ``REAL``：调用 OpenAI 兼容 Embedding 端点；
- 未配置密钥但允许降级（``DEMO_MODE=true`` 或非生产）→ ``MOCK``：
  输出确定性伪随机单位向量，保证离线联调可跑；
- 生产环境未配置密钥 → ``UNAVAILABLE``：``embed_texts`` 抛
  :class:`EmbeddingUnavailableError`，调用方须显式返回“不可用”，
  **不得**回退伪随机向量的伪装结果。

类型适配：pgvector / psycopg 只认 :class:`pgvector.Vector`，
:meth:`Embedder.embed_texts` 返回 ``list[Vector]``；写库/检索严禁直接传 Python list。
"""

from __future__ import annotations

import hashlib
import logging
import math
import random
from typing import List, Optional, Sequence

from openai import OpenAI

from app.config import Settings, get_settings
from app.db.session import Vector

logger = logging.getLogger(__name__)

# 单次 embeddings.create 请求的最大文本条数（OpenAI/兼容端点 2048 上限内保守取值）
DEFAULT_BATCH_SIZE = 64


class EmbeddingUnavailableError(RuntimeError):
    """Embedding 不可用（生产未配置真实密钥，且不允许降级）。"""


class Embedder:
    """文本向量化客户端（OpenAI 兼容端点 + 受控 Mock 兜底）。"""

    def __init__(
        self,
        settings: Optional[Settings] = None,
        *,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        dim: Optional[int] = None,
        batch_size: int = DEFAULT_BATCH_SIZE,
        force_mock: Optional[bool] = None,
    ) -> None:
        cfg = settings or get_settings()
        self.settings = cfg
        self.model = model or cfg.embedding_model
        self.dim = dim or cfg.embedding_dim
        self.batch_size = max(1, batch_size)

        effective_key = api_key if api_key is not None else cfg.embedding_api_key_effective
        effective_base_url = base_url or cfg.embedding_base_url

        self._client = None
        self.mock = False
        self.available = True
        self.unavailable_reason = ""

        if force_mock is True:
            # 显式要求离线（测试/联调）：即使生产也尊重调用方显式意图
            self.mock = True
        elif effective_key:
            self._client = OpenAI(api_key=effective_key, base_url=effective_base_url)
            logger.info(
                "Embedder 就绪(REAL) base_url=%s model=%s dim=%d",
                effective_base_url,
                self.model,
                self.dim,
            )
        elif cfg.mock_allowed:
            self.mock = True
        else:
            self.available = False
            self.unavailable_reason = (
                "生产环境未配置 EMBEDDING_API_KEY/DEEPSEEK_API_KEY，且不允许降级（DEMO_MODE=false）"
            )

        if self.mock:
            logger.warning(
                "Embedder 进入 MOCK 模式（确定性伪随机向量）dim=%d model=%s app_env=%s demo=%s",
                self.dim,
                self.model,
                cfg.app_env,
                cfg.demo_mode,
            )
        elif not self.available:
            logger.error("Embedder 不可用：%s", self.unavailable_reason)

    @property
    def is_mock(self) -> bool:
        """是否处于 Mock 模式。"""
        return self.mock

    @property
    def is_available(self) -> bool:
        """是否可用于向量化（真实或允许的降级）。"""
        return self.available

    @property
    def mode(self) -> str:
        """能力模式：REAL / MOCK / UNAVAILABLE。"""
        if not self.available:
            return "UNAVAILABLE"
        return "MOCK" if self.mock else "REAL"

    def embed_texts(self, texts: Sequence[str]) -> List[Vector]:
        """批量向量化，返回 ``list[Vector]``（顺序与入参一致）。"""
        if not texts:
            return []
        if not self.available:
            raise EmbeddingUnavailableError(
                self.unavailable_reason or "Embedding 不可用"
            )
        if self.mock:
            return [Vector(self._mock_vector(t)) for t in texts]

        vectors: List[Vector] = []
        for start in range(0, len(texts), self.batch_size):
            batch = list(texts[start : start + self.batch_size])
            response = self._client.embeddings.create(model=self.model, input=batch)
            ordered = sorted(response.data, key=lambda item: item.index)
            if len(ordered) != len(batch):
                logger.warning("Embedding 返回条数(%d)与请求条数(%d)不一致", len(ordered), len(batch))
            for item in ordered:
                vectors.append(Vector(list(item.embedding)))
        return vectors

    def embed_text(self, text: str) -> Vector:
        """单条文本向量化（便捷方法）。"""
        return self.embed_texts([text])[0]

    # ------------------------------------------------------------ Mock 内部

    def _mock_vector(self, text: str) -> List[float]:
        """由文本 sha256 播种的确定性伪随机单位向量（仅 Mock 模式使用）。"""
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        rng = random.Random(digest)
        values = [rng.uniform(-1.0, 1.0) for _ in range(self.dim)]
        norm = math.sqrt(sum(v * v for v in values)) or 1.0
        return [v / norm for v in values]


def build_embedder(**kwargs) -> Embedder:
    """按全局配置构建 :class:`Embedder` 的便捷工厂。"""
    return Embedder(**kwargs)


__all__ = [
    "Embedder",
    "EmbeddingUnavailableError",
    "build_embedder",
    "DEFAULT_BATCH_SIZE",
]

