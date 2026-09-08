"""知识库管理 DTO（对齐 docs/api-spec.yaml /ai/v1/knowledge/docs）。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class KnowledgeDocItem(BaseModel):
    """知识库文档元信息（由 t_knowledge_chunk 按 doc_title 聚合推导）。"""

    id: int = Field(description="列表序号（按入库时间倒序稳定生成）")
    title: str = Field(description="文档标题（= 入库 doc_title）")
    tenantId: str = Field(description="归属租户（global 为共享知识库）")
    tenantName: str = Field(default="", description="租户显示名（前端补充或回退 ID）")
    source: str = Field(default="", description="原始来源文件名")
    category: str = Field(default="GENERAL", description="知识分类")
    chunks: int = Field(description="切片数")
    status: str = Field(default="READY", description="处理状态（同步入库成功即 READY）")
    createdAt: str = Field(description="最早切片入库时间（ISO 字符串）")


class KnowledgeUploadResponse(BaseModel):
    """上传并入库成功的摘要。"""

    title: str
    tenantId: str
    category: str
    chunks: int
    embeddingMode: str = Field(description="Mock / Real（未配置密钥时自动 Mock 离线可跑）")


class KnowledgeDeleteRequest(BaseModel):
    """删除文档请求（仅删除该租户自身文档；global 共享文档需显式 tenantId=global）。"""

    tenantId: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=255)


class KnowledgeDeleteResponse(BaseModel):
    """删除文档结果（已删除的切片行数）。"""

    deleted: int
