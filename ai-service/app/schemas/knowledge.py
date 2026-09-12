"""知识库管理 DTO（对齐 docs/api-spec.yaml /ai/v1/knowledge/docs）。

阶段 F 增强：文档元信息新增版本号、内容哈希、上传者、审核状态与页数，
配套审核/版本查询 DTO；租户与上传者一律服务端从已验证身份推导。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

# 文档审核状态
ReviewStatus = Literal["PENDING_REVIEW", "APPROVED", "REJECTED"]


class KnowledgeDocItem(BaseModel):
    """知识库文档元信息（chunk 聚合 + 登记表元数据合并）。"""

    id: int = Field(description="列表序号（按入库时间倒序稳定生成）")
    title: str = Field(description="文档标题（= 入库 doc_title）")
    tenantId: str = Field(description="归属租户（global 为共享知识库）")
    tenantName: str = Field(default="", description="租户显示名（前端补充或回退 ID）")
    source: str = Field(default="", description="原始来源文件名")
    category: str = Field(default="GENERAL", description="知识分类")
    chunks: int = Field(description="切片数")
    status: str = Field(default="READY", description="处理状态（同步入库成功即 READY）")
    createdAt: str = Field(description="最早切片入库时间（ISO 字符串）")
    # ---- 阶段 F 新增元数据 ----
    version: int = Field(default=1, description="当前版本号（每次覆盖上传 +1）")
    contentSha256: str = Field(default="", description="当前版本内容 SHA-256（用于溯源/去重）")
    fileSize: int = Field(default=0, description="当前版本原始文件字节数")
    pages: int = Field(default=1, description="解析页数（文本文件为 1）")
    uploadedBy: str = Field(default="", description="上传者用户名")
    reviewStatus: ReviewStatus = Field(
        default="PENDING_REVIEW", description="审核状态：PENDING_REVIEW/APPROVED/REJECTED"
    )
    reviewedBy: str = Field(default="", description="审核人用户名")
    reviewedAt: str = Field(default="", description="审核时间（ISO 字符串）")
    reviewComment: str = Field(default="", description="审核意见")


class KnowledgeUploadResponse(BaseModel):
    """上传并入库成功的摘要。"""

    title: str
    tenantId: str
    category: str
    chunks: int
    embeddingMode: str = Field(description="Real / Mock / Unavailable")
    version: int = Field(default=1, description="本次入库后的版本号")
    pages: int = Field(default=1, description="解析页数")
    fileSize: int = Field(default=0, description="原始文件字节数")
    contentSha256: str = Field(default="", description="内容 SHA-256")
    reviewStatus: ReviewStatus = Field(
        default="PENDING_REVIEW", description="审核状态（新上传默认待审核）"
    )


class KnowledgeDeleteRequest(BaseModel):
    """删除文档请求（仅删除该租户自身文档；global 共享文档需显式 tenantId=global）。"""

    tenantId: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=255)


class KnowledgeDeleteResponse(BaseModel):
    """删除文档结果（已删除的切片行数）。"""

    deleted: int


class KnowledgeReviewRequest(BaseModel):
    """文档审核请求（APPROVED 通过 / REJECTED 驳回）。"""

    tenantId: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=255)
    status: Literal["APPROVED", "REJECTED"] = Field(description="审核结论")
    comment: str = Field(default="", max_length=500, description="审核意见（可选）")


class KnowledgeReviewResponse(BaseModel):
    """审核结果。"""

    title: str
    tenantId: str
    reviewStatus: ReviewStatus
    reviewedBy: str
    reviewedAt: str
    comment: str = ""


class KnowledgeVersionItem(BaseModel):
    """文档版本历史条目（append-only）。"""

    version: int
    contentSha256: str
    fileSize: int
    chunks: int
    uploadedBy: str
    uploadedAt: str

