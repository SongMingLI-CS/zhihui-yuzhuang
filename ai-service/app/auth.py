"""ai-service 身份验证与授权（与业务后端 JWT 契约一致）。

背景（审计 P0-1）：AI 侧历史上无身份校验，任何公网调用者都能上传/删除知识、
指定任意 ``tenant_id``。本模块提供：

- HS256 JWT 校验（与 backend ``yuzhuang.auth.secret`` 同密钥、同载荷契约）；
- 会话 Cookie（``yz_session``）兼容读取，支持浏览器同源调用；
- 角色白名单校验（知识上传/删除、营销生成、管理型查询）；
- 匿名请求的公共知识域解析（QA 仅可访问 global，且限流，见 ``app/ratelimit.py``）。

约定：``tenant_id`` 一律从<b>已验证身份</b>推导，绝不信任 ``X-Tenant-Id`` 头 / 表单值。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from typing import Iterable, Optional, Sequence

from fastapi import Request
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.schemas.base import ApiResponse

# 与 backend application.yml 的 yuzhuang.auth.secret 默认值保持一致（仅开发/演示）；
# 生产必须通过 AUTH_JWT_SECRET 注入高熵密钥（backend 端启动会 fail-fast）。
DEV_JWT_SECRET = "yuzhuang-demo-jwt-secret-0123456789abcdef"

SESSION_COOKIE_NAME = "yz_session"

# 角色常量（与 backend UserRole 对齐）
ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN"
ROLE_GOVERNMENT = "GOVERNMENT"
ROLE_VILLAGE = "VILLAGE"
ROLE_COOPERATIVE = "COOPERATIVE"
ROLE_FARMER = "FARMER"
ROLE_CONSUMER = "CONSUMER"

# 可写知识库/发起营销的角色
KNOWLEDGE_WRITE_ROLES = (ROLE_COOPERATIVE, ROLE_VILLAGE, ROLE_PLATFORM_ADMIN)
# 可读知识库（含政府只读）的角色
KNOWLEDGE_READ_ROLES = (
    ROLE_COOPERATIVE,
    ROLE_VILLAGE,
    ROLE_PLATFORM_ADMIN,
    ROLE_GOVERNMENT,
)
# 公共知识域（匿名 QA 只允许访问该租户域）
PUBLIC_TENANT_ID = "global"


def _b64url_decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


@dataclass(frozen=True)
class Principal:
    """已验证的调用方身份。"""

    user_id: Optional[int]
    username: str
    tenant_id: str
    role: str

    def has_role(self, roles: Iterable[str]) -> bool:
        return self.role in set(roles)


class AuthError(Exception):
    """认证/授权错误（携带 HTTP 状态与业务码）。"""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


def jwt_secret() -> str:
    """生效的 JWT 密钥（未配置时回退开发默认值）。"""
    settings = get_settings()
    return settings.auth_jwt_secret or DEV_JWT_SECRET


def create_token(
    username: str,
    role: str,
    tenant_id: str,
    *,
    user_id: int = 0,
    display_name: str = "",
    expire_seconds: int = 3600,
    secret: Optional[str] = None,
) -> str:
    """签发 HS256 JWT（与 backend 契约一致；供本地联调 / 测试使用）。"""
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": username,
        "userId": user_id,
        "tenantId": tenant_id,
        "role": role,
        "displayName": display_name or username,
        "iat": now,
        "exp": now + expire_seconds,
    }
    signing_input = (
        _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    )
    key = (secret if secret is not None else jwt_secret()).encode("utf-8")
    signature = hmac.new(key, signing_input.encode("ascii"), hashlib.sha256).digest()
    return signing_input + "." + _b64url_encode(signature)


def parse_token(token: str, *, secret: Optional[str] = None) -> Principal:
    """校验并解析 JWT；无效/过期抛 :class:`AuthError`。"""
    if not token:
        raise AuthError(401, "A1002", "未携带访问令牌")
    parts = token.split(".")
    if len(parts) != 3:
        raise AuthError(401, "A1002", "令牌格式非法")
    signing_input = f"{parts[0]}.{parts[1]}"
    key = (secret if secret is not None else jwt_secret()).encode("utf-8")
    expected = hmac.new(key, signing_input.encode("ascii"), hashlib.sha256).digest()
    try:
        actual = _b64url_decode(parts[2])
    except Exception as exc:  # noqa: BLE001
        raise AuthError(401, "A1002", "令牌签名编码非法") from exc
    if not hmac.compare_digest(expected, actual):
        raise AuthError(401, "A1002", "令牌签名无效")
    try:
        payload = json.loads(_b64url_decode(parts[1]).decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise AuthError(401, "A1002", "令牌载荷解析失败") from exc
    exp = payload.get("exp")
    if not isinstance(exp, (int, float)) or exp < time.time():
        raise AuthError(401, "A1002", "令牌已过期")
    return Principal(
        user_id=payload.get("userId"),
        username=payload.get("sub") or "",
        tenant_id=payload.get("tenantId") or PUBLIC_TENANT_ID,
        role=payload.get("role") or "",
    )


def extract_token(request: Request) -> Optional[str]:
    """从 Authorization 头或会话 Cookie 提取令牌。"""
    authorization = request.headers.get("Authorization") or request.headers.get("authorization")
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
        if token:
            return token
    cookie = request.cookies.get(SESSION_COOKIE_NAME)
    return cookie or None


def optional_principal(request: Request) -> Optional[Principal]:
    """解析匿名可选身份：无令牌返回 ``None``；无效令牌按匿名处理（不抛错）。"""
    token = extract_token(request)
    if not token:
        return None
    try:
        return parse_token(token)
    except AuthError:
        return None


def resolve_principal(request: Request, allowed_roles: Sequence[str]) -> Principal:
    """强制认证 + 角色校验；未认证抛 401，越权抛 403。"""
    token = extract_token(request)
    if not token:
        raise AuthError(401, "A1002", "未登录或登录已过期")
    principal = parse_token(token)
    if allowed_roles and not principal.has_role(allowed_roles):
        raise AuthError(403, "A1003", "无权限访问该资源")
    return principal


def error_response(exc: AuthError, request_id: str) -> JSONResponse:
    """把 :class:`AuthError` 转换为统一错误包裹响应。"""
    payload = ApiResponse.fail(code=exc.code, message=exc.message, request_id=request_id)
    return JSONResponse(status_code=exc.status_code, content=payload.model_dump(mode="json"))


__all__ = [
    "AuthError",
    "Principal",
    "create_token",
    "error_response",
    "extract_token",
    "jwt_secret",
    "optional_principal",
    "parse_token",
    "resolve_principal",
    "PUBLIC_TENANT_ID",
    "KNOWLEDGE_READ_ROLES",
    "KNOWLEDGE_WRITE_ROLES",
    "ROLE_COOPERATIVE",
    "ROLE_GOVERNMENT",
    "ROLE_PLATFORM_ADMIN",
    "ROLE_VILLAGE",
    "SESSION_COOKIE_NAME",
]
