"""ai-service 应用配置（基于 pydantic-settings）。

环境变量契约与 ``deploy/docker-compose.yml`` 中 ai-service 服务一致：
- ``DATABASE_URL``        PostgreSQL / pgvector 完整连接串
- ``DEEPSEEK_API_KEY``    DeepSeek API 密钥（LLM）
- ``DEEPSEEK_BASE_URL``   DeepSeek API 基地址
- ``EMBEDDING_*``         Embedding 独立配置（与 LLM 端点解耦，兼容 OpenAI 规范）：
  密钥 / 基址 / 模型 / 维度。``EMBEDDING_API_KEY`` 为空时回退用 ``DEEPSEEK_API_KEY``。

约定：
- 本地开发默认连接 docker compose 发布到宿主机的 PostgreSQL（localhost:5432），
  账号/库名与 ``deploy/docker-compose.yml`` 的 ``POSTGRES_*`` 默认值对齐；
- 容器内通过 ``DATABASE_URL`` 覆盖为内网服务名 ``postgres``
  （即 ``rural-revitalization-postgres``，见 .clinerules 容器环境感知）。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, computed_field
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai-service 根目录：app/config.py 的上级是 app/，上上级是 ai-service/
SERVICE_ROOT = Path(__file__).resolve().parent.parent

# 本地开发默认连接串（与 deploy/docker-compose.yml 的 POSTGRES_* 默认值对齐）
LOCAL_DATABASE_URL = "postgresql://rural_user:rural_password@localhost:5432/rural_revitalization"
DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
# Embedding 默认基址：OpenAI 兼容端点（DeepSeek 暂无官方 Embedding API，端点需独立配置）
DEFAULT_EMBEDDING_BASE_URL = "https://api.openai.com/v1"


class Settings(BaseSettings):
    """全局配置：从环境变量 / ai-service/.env 读取。"""

    model_config = SettingsConfigDict(
        env_file=SERVICE_ROOT / ".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---------- 应用 ----------
    app_name: str = "智汇于庄 · AI 服务"
    app_env: str = "local"  # local / docker / prod
    debug: bool = False
    api_prefix: str = "/ai/v1"  # 与 docs/api-spec.yaml 的 ai servers 前缀一致

    # ---------- PostgreSQL / pgvector ----------
    database_url: str = LOCAL_DATABASE_URL
    db_pool_min_size: int = Field(default=2, ge=1, description="连接池最小连接数")
    db_pool_max_size: int = Field(default=10, ge=1, description="连接池最大连接数")
    db_connect_timeout: float = Field(default=10.0, ge=1.0, description="连接/获取连接超时（秒）")
    db_command_timeout: float = Field(default=30.0, ge=1.0, description="单条命令超时（秒）")

    # ---------- 身份验证（与业务后端同密钥 / 同契约）----------
    # AUTH_JWT_SECRET：HS256 密钥，必须与 backend 的 yuzhuang.auth.secret 一致。
    # 留空时回退 app.auth.DEV_JWT_SECRET（仅开发/演示），生产必须显式注入。
    auth_jwt_secret: str = ""
    # 演示开关：仅 DEMO_MODE=true 时允许伪随机 Embedding 与离线营销模板（见阶段 F）。
    demo_mode: bool = False

    # ---------- DeepSeek (LLM) ----------
    deepseek_api_key: str = ""
    deepseek_base_url: str = DEFAULT_DEEPSEEK_BASE_URL

    # ---------- Embedding（与 LLM 端点解耦，OpenAI 兼容规范）----------
    # DeepSeek 暂无官方 Embedding API：Embedding 必须走独立配置项，
    # 仅当 EMBEDDING_API_KEY 为空时才回退使用 DEEPSEEK_API_KEY。
    embedding_api_key: str = ""
    embedding_base_url: str = DEFAULT_EMBEDDING_BASE_URL
    embedding_model: str = "text-embedding-3-small"
    embedding_dim: int = Field(default=1536, ge=1, description="Embedding 向量维度")

    # ---------- RAG 检索（双路召回）----------
    rag_hybrid_enabled: bool = Field(
        default=True, description="是否启用双路召回（向量 + 关键词 RRF 融合）"
    )
    rag_keyword_min_score: float = Field(
        default=0.3, ge=0.0, le=1.0, description="关键词召回最小命中比例（0~1）"
    )

    @computed_field  # type: ignore[misc]
    @property
    def deepseek_enabled(self) -> bool:
        """是否已配置可用的 DeepSeek 密钥。"""
        return bool(self.deepseek_api_key)

    @property
    def is_production(self) -> bool:
        """是否生产环境（production / prod）。"""
        return self.app_env.strip().lower() in {"production", "prod"}

    @property
    def mock_allowed(self) -> bool:
        """是否允许伪随机 Embedding / 离线营销模板。

        仅当显式 ``DEMO_MODE=true`` 或运行于非生产环境时允许；
        生产环境缺少真实能力时必须显式报“不可用”，不得伪装成真实 AI。
        """
        return self.demo_mode or not self.is_production

    @property
    def embedding_configured(self) -> bool:
        """是否配置了真实 Embedding 密钥（EMBEDDING_API_KEY 或回退 DEEPSEEK_API_KEY）。"""
        return bool(self.embedding_api_key_effective)

    @property
    def embedding_api_key_effective(self) -> str:
        """实际生效的 Embedding 密钥：EMBEDDING_API_KEY 为空时回退 DEEPSEEK_API_KEY。"""
        return self.embedding_api_key or self.deepseek_api_key

    @property
    def embedding_enabled(self) -> bool:
        """是否可用真实 Embedding（配置了密钥）。"""
        return bool(self.embedding_api_key_effective)


@lru_cache
def get_settings() -> Settings:
    """读取（并缓存）配置单例。"""
    return Settings()
