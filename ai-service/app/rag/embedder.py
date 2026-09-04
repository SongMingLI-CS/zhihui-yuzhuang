"""向量化客户端：封装 OpenAI SDK 的 ``client.embeddings.create``。

DeepSeek 暂无官方 Embedding API，因此 Embedding 的密钥 / 基址 / 模型 / 维度
均独立配置（见 :mod:`app.config` 的 ``EMBEDDING_*``），走 OpenAI 兼容端点。

Mock 模式：
- 当未配置任何有效 Embedding 密钥（``EMBEDDING_API_KEY`` 为空且回退的
  ``DEEPSEEK_API_KEY`` 也为空）时，:class:`Embedder` 自动进入 Mock 模式，
  输出**确定性**伪随机单位向量（同一文本 → 同一向量），
  保证断网 / 离线单测与本地联调可跑通；
- 也可通过 ``force_mock=True`` 显式开启。

类型适配（重要）：pgvector / psycopg 只认 :class:`pgvector.Vector` 对象，
:meth:`Embedder.embed_texts` 返回的正是 ``list[Vector]``（已用
``app.db.session.Vector`` 包装）。写库 / 检索时**严禁**直接传 Python list，
否则会触发 ``DatatypeMismatch``。
"""

from __future__ import annotations

import hashlib
import logging
import math
import random
from typing import List, Optional, Sequence, Union

from openai import OpenAI

from app.config import Settings, get_settings
from app.db.session import Vector

logger = logging.getLogger(__name__)

# 单次 embeddings.create 请求的最大文本条数（OpenAI/兼容端点 2048 上限内保守取值）
DEFAULT_BATCH_SIZE = 64


class Embedder:
    """文本向量化客户端（OpenAI 兼容 Embedding 端点 + Mock 兜底）。"""

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

        # 显式传入 force_mock 时遵从其开关；否则按「是否配置了有效密钥」自动判定
        if force_mock is None:
            force_mock = not bool(effective_key)
        self.mock = force_mock

        if self.mock:
            self._client = None
            logger.warning(
                "Embedder 进入 Mock 模式（未配置有效 Embedding 密钥），"
                "将生成确定性伪随机向量 dim=%d model=%s",
                self.dim,
                self.model,
            )
        else:
            self._client = OpenAI(api_key=effective_key, base_url=effective_base_url)
            logger.info(
                "Embedder 就绪 base_url=%s model=%s dim=%d",
                effective_base_url,
                self.model,
                self.dim,
            )

    @property
    def is_mock(self) -> bool:
        """是否处于 Mock 模式。"""
        return self.mock

    def embed_texts(self, texts: Sequence[str]) -> List[Vector]:
        """批量向量化，返回 ``list[Vector]``（顺序与入参一致）。

        Mock 模式下直接生成确定性伪随机向量；真实模式下按 ``batch_size``
        分批调用 ``client.embeddings.create``。
        """
        if not texts:
            return []
        if self.mock:
            return [Vector(self._mock_vector(t)) for t in texts]

        vectors: List[Vector] = []
        for start in range(0, len(texts), self.batch_size):
            batch = list(texts[start : start + self.batch_size])
            response = self._client.embeddings.create(model=self.model, input=batch)
            # 兼容端点按序返回 data，显式按 index 排序保证顺序稳定
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
        """由文本 sha256 播种的确定性伪随机单位向量。

        保证：同一文本在任意运行 / 机器上得到相同向量（离线单测可断言相等）；
        输出为单位向量，便于用 ``<=>`` 余弦距离做检索联调。
        """
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        rng = random.Random(digest)  # 接受 bytes 种子，确定性可复现
        values = [rng.uniform(-1.0, 1.0) for _ in range(self.dim)]
        norm = math.sqrt(sum(v * v for v in values)) or 1.0
        return [v / norm for v in values]


def build_embedder(**kwargs) -> Embedder:
    """按全局配置构建 :class:`Embedder` 的便捷工厂。"""
    return Embedder(**kwargs)


__all__ = [
    "Embedder",
    "build_embedder",
    "DEFAULT_BATCH_SIZE",
]
