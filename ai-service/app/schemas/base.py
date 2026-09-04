"""基础 DTO：统一响应包裹。

字段命名 / 语义与 ``docs/api-spec.yaml`` 中 ``ApiResponse`` 完全一致：
``code / message / data / timestamp / requestId``。
"""

from __future__ import annotations

import time
import uuid
from typing import Generic, Optional, TypeVar

from pydantic import BaseModel, Field

# 业务状态码约定（docs/api-spec.yaml：code="00000" 代表成功）
CODE_SUCCESS = "00000"
MSG_SUCCESS = "操作成功"

T = TypeVar("T")


def utc_now_ms() -> int:
    """当前 UTC 毫秒级时间戳（契约 timestamp 为 int64 毫秒）。"""
    return int(time.time() * 1000)


def new_request_id() -> str:
    """生成链路追踪 ID，形如 ``req-<uuid>``（契约示例为 req-xxx）。"""
    return f"req-{uuid.uuid4()}"


class ApiResponse(BaseModel, Generic[T]):
    """统一响应包裹（泛型：data 承载具体载荷）。"""

    code: str = Field(default=CODE_SUCCESS, description="业务状态码 (00000 代表成功)")
    message: str = Field(default=MSG_SUCCESS, description="响应或错误信息")
    data: Optional[T] = Field(default=None, description="响应具体载荷")
    timestamp: int = Field(default_factory=utc_now_ms, description="毫秒级时间戳")
    requestId: str = Field(default_factory=new_request_id, description="链路追踪 ID")

    @classmethod
    def ok(
        cls,
        data: Optional[T] = None,
        *,
        request_id: str = "",
        message: str = MSG_SUCCESS,
    ) -> "ApiResponse[T]":
        """构造成功响应。"""
        return cls(code=CODE_SUCCESS, message=message, data=data, request_id=request_id)

    @classmethod
    def fail(cls, code: str, message: str, *, request_id: str = "") -> "ApiResponse[None]":
        """构造业务失败响应（data 为空）。"""
        return cls(code=code, message=message, data=None, request_id=request_id)
