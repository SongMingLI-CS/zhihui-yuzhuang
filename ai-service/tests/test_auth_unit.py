"""ai-service 身份验证/授权单元测试（离线、无数据库依赖）。

覆盖阶段 B 验收点：AI 端点鉴权（签发/校验/过期/篡改/角色白名单/匿名公共域）。
"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pytest  # noqa: E402

from app.auth import (  # noqa: E402
    KNOWLEDGE_READ_ROLES,
    KNOWLEDGE_WRITE_ROLES,
    PUBLIC_TENANT_ID,
    ROLE_COOPERATIVE,
    ROLE_FARMER,
    ROLE_GOVERNMENT,
    ROLE_VILLAGE,
    AuthError,
    create_token,
    parse_token,
)

SECRET = "unit-test-secret-0123456789abcdef"


def test_token_roundtrip_parses_claims() -> None:
    token = create_token("coop001", ROLE_COOPERATIVE, "tenant_a", user_id=7, secret=SECRET)
    principal = parse_token(token, secret=SECRET)
    assert principal.username == "coop001"
    assert principal.role == ROLE_COOPERATIVE
    assert principal.tenant_id == "tenant_a"
    assert principal.user_id == 7


def test_tampered_token_rejected() -> None:
    token = create_token("coop001", ROLE_COOPERATIVE, "tenant_a", secret=SECRET)
    tampered = token[:-2] + ("aa" if not token.endswith("aa") else "bb")
    with pytest.raises(AuthError) as exc:
        parse_token(tampered, secret=SECRET)
    assert exc.value.code == "A1002"
    assert exc.value.status_code == 401


def test_wrong_secret_rejected() -> None:
    token = create_token("coop001", ROLE_COOPERATIVE, "tenant_a", secret=SECRET)
    with pytest.raises(AuthError):
        parse_token(token, secret="another-secret-0123456789abcdef")


def test_expired_token_rejected() -> None:
    token = create_token(
        "coop001", ROLE_COOPERATIVE, "tenant_a", expire_seconds=-10, secret=SECRET
    )
    with pytest.raises(AuthError) as exc:
        parse_token(token, secret=SECRET)
    assert "过期" in exc.value.message


def test_role_whitelist_semantics() -> None:
    cooperative = parse_token(
        create_token("coop001", ROLE_COOPERATIVE, "tenant_a", secret=SECRET), secret=SECRET
    )
    farmer = parse_token(
        create_token("farmer001", ROLE_FARMER, "tenant_a", secret=SECRET), secret=SECRET
    )
    government = parse_token(
        create_token("gov001", ROLE_GOVERNMENT, "tenant_a", secret=SECRET), secret=SECRET
    )
    village = parse_token(
        create_token("admin", ROLE_VILLAGE, "tenant_a", secret=SECRET), secret=SECRET
    )

    # 知识写：商家/村委/平台可写，农户不可写，政府只读
    assert cooperative.has_role(KNOWLEDGE_WRITE_ROLES)
    assert village.has_role(KNOWLEDGE_WRITE_ROLES)
    assert not farmer.has_role(KNOWLEDGE_WRITE_ROLES)
    assert not government.has_role(KNOWLEDGE_WRITE_ROLES)
    # 知识读：政府可读
    assert government.has_role(KNOWLEDGE_READ_ROLES)
    assert not farmer.has_role(KNOWLEDGE_READ_ROLES)


def test_public_tenant_constant() -> None:
    assert PUBLIC_TENANT_ID == "global"
