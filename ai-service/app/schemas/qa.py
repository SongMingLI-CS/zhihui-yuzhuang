"""农技问答接口模型。

对齐 ``docs/api-spec.yaml`` 契约：``AgriQARequest`` / ``AgriQAResponse`` / ``Citation``。
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field

# 问题分类（docs/api-spec.yaml：DISEASE_PEST / FERTILIZER / POLICY / GENERAL）
AgriCategory = Literal["DISEASE_PEST", "FERTILIZER", "POLICY", "GENERAL"]


class AgriQARequest(BaseModel):
    """农技问答请求体（POST /ai/v1/qa/ask）。"""

    question: str = Field(
        ...,
        min_length=2,
        max_length=500,
        description="农户或技术人员咨询的问题",
        examples=["冬小麦在返青期发黄，叶尖有褐色小点，应该打什么药？"],
    )
    category: AgriCategory = Field(
        default="GENERAL",
        description="问题分类 (病虫害/水肥/政策/通用)",
    )
    sessionId: Optional[str] = Field(
        default=None,
        description="上下文多轮对话会话 ID",
        examples=["sess_agri_8812"],
    )


class Citation(BaseModel):
    """检索召回的高置信度引用来源（防幻觉溯源）。"""

    docTitle: str = Field(..., description="来源文档标题")
    pageNumber: Optional[int] = Field(default=None, description="页码（可为空）")
    chunkText: str = Field(..., description="命中的文本片段")
    similarityScore: float = Field(..., ge=0.0, le=1.0, description="语义相似度 0~1")


class AgriQAResponse(BaseModel):
    """农技问答响应载荷（data 字段内容）。"""

    answer: str = Field(..., description="DeepSeek 结合召回语料生成的严谨回答")
    disclaimer: Optional[str] = Field(
        default=None,
        description="农技免责声明（如安全用药提示）",
    )
    citations: list[Citation] = Field(
        default_factory=list,
        description="pgvector 语义检索召回的高置信度依据",
    )
