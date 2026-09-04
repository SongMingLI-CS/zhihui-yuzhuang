"""DeepSeek Chat 异步客户端（OpenAI 兼容 SDK 封装）。

特性：
- 读取 ``DEEPSEEK_API_KEY`` / ``DEEPSEEK_BASE_URL``（见 :mod:`app.config`）；
- 未配置密钥时 ``available=False``，:meth:`DeepSeekChatClient.achat` 抛
  :class:`LLMUnavailableError`，由编排层降级返回，**绝不产生 NoneType 崩溃**；
- API 超时 / 连接失败 / 状态错误分别映射为
  :class:`LLMTimeoutError` / :class:`LLMServiceError`，便于上层精确降级。

使用 ``AsyncOpenAI``，接口与 openai SDK 完全兼容；DeepSeek 官方兼容端点：
``base_url=https://api.deepseek.com``、``model=deepseek-chat``。
"""

from __future__ import annotations

import logging
from typing import List, Optional

from openai import (
    APIConnectionError,
    APITimeoutError,
    APIStatusError,
    AsyncOpenAI,
)

from app.config import Settings, get_settings

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "deepseek-chat"
DEFAULT_TEMPERATURE = 0.2
DEFAULT_MAX_TOKENS = 1024
DEFAULT_TIMEOUT = 60.0  # 秒（连接 / 读取总超时）


class LLMUnavailableError(RuntimeError):
    """未配置可用密钥 / 模型，LLM 不可用。"""


class LLMTimeoutError(LLMUnavailableError):
    """LLM 调用超时。"""


class LLMServiceError(RuntimeError):
    """LLM 调用返回错误 / 网络异常。"""


class DeepSeekChatClient:
    """OpenAI 兼容的 DeepSeek Chat 客户端（异步）。"""

    def __init__(
        self,
        settings: Optional[Settings] = None,
        *,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        temperature: float = DEFAULT_TEMPERATURE,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        cfg = settings or get_settings()
        self.settings = cfg
        self.model = model or DEFAULT_MODEL
        self.temperature = float(temperature)
        self.max_tokens = max(int(max_tokens), 1)
        self.timeout = float(timeout)

        effective_key = api_key if api_key is not None else cfg.deepseek_api_key
        effective_base_url = base_url or cfg.deepseek_base_url

        self.available = bool(effective_key)
        if not self.available:
            self._client: Optional[AsyncOpenAI] = None
            logger.warning(
                "DeepSeek 未配置 DEEPSEEK_API_KEY，LLM 不可用（问答将走降级/兜底路径）"
            )
        else:
            self._client = AsyncOpenAI(
                api_key=effective_key,
                base_url=effective_base_url,
                timeout=self.timeout,
                max_retries=1,
            )
            logger.info("DeepSeek Chat 就绪 base_url=%s model=%s", effective_base_url, self.model)

    async def achat(
        self,
        messages: List[dict],
        *,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        timeout: Optional[float] = None,
    ) -> str:
        """发起一次 Chat 补全，返回助手正文（已去除首尾空白）。

        异常语义：不可用 -> :class:`LLMUnavailableError`；
        超时 -> :class:`LLMTimeoutError`；其余 -> :class:`LLMServiceError`。
        """
        if not self.available or self._client is None:
            raise LLMUnavailableError(
                "未配置 DEEPSEEK_API_KEY，大模型服务不可用（请配置后重试）"
            )

        effective_timeout = timeout or self.timeout
        try:
            response = await self._client.chat.completions.create(
                model=self.model,
                messages=messages,
                temperature=self.temperature if temperature is None else float(temperature),
                max_tokens=self.max_tokens if max_tokens is None else int(max_tokens),
                timeout=effective_timeout,
            )
        except APITimeoutError as exc:
            logger.warning("DeepSeek 调用超时（%.1fs）: %s", effective_timeout, exc)
            raise LLMTimeoutError(
                f"DeepSeek 调用超时（>{effective_timeout:.0f}s），请稍后重试"
            ) from exc
        except (APIConnectionError, APIStatusError) as exc:
            logger.warning("DeepSeek 调用异常: %s", exc)
            raise LLMServiceError(f"DeepSeek 调用失败：{exc}") from exc
        except Exception as exc:  # noqa: BLE001 - 兜底捕获未知 SDK 异常
            logger.exception("DeepSeek 未知异常: %s", exc)
            raise LLMServiceError(f"DeepSeek 调用失败：{exc}") from exc

        try:
            content = response.choices[0].message.content or ""
        except (IndexError, AttributeError):
            logger.warning("DeepSeek 响应缺少 choices[0].message.content")
            content = ""
        return content.strip()


def build_deepseek_chat(**kwargs) -> DeepSeekChatClient:
    """按全局配置构建 :class:`DeepSeekChatClient` 的便捷工厂。"""
    return DeepSeekChatClient(**kwargs)


__all__ = [
    "DeepSeekChatClient",
    "build_deepseek_chat",
    "LLMUnavailableError",
    "LLMTimeoutError",
    "LLMServiceError",
    "DEFAULT_MODEL",
]
