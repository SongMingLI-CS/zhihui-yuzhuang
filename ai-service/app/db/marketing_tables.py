"""营销生成任务表结构（阶段 F：生成/审批/发布持久化）。

``t_marketing_task`` 一行 = 一次营销生成任务，含：
- 输入（request_payload）与输出（response_payload）原始 JSON，便于追溯与版本对比；
- 合规结果（compliance_score/passed）快照；
- 审批状态机（PENDING_HUMAN_REVIEW → APPROVED / REJECTED）与审批人/时间/意见；
- 发布留痕（published_at，仅 APPROVED 后可写）。

幂等：``CREATE TABLE IF NOT EXISTS``，可安全重复调用。
"""

from __future__ import annotations

import psycopg

from app.config import get_settings

settings = get_settings()

MARKETING_TASK_TABLE = "t_marketing_task"

# 审批状态
STATUS_PENDING = "PENDING_HUMAN_REVIEW"
STATUS_APPROVED = "APPROVED"
STATUS_REJECTED = "REJECTED"

CREATE_MARKETING_TASK_TABLE_SQL = f"""
CREATE TABLE IF NOT EXISTS {MARKETING_TASK_TABLE} (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    product_name      VARCHAR(128) NOT NULL,
    request_payload   JSONB        NOT NULL DEFAULT '{{}}'::jsonb,
    response_payload  JSONB        NOT NULL DEFAULT '{{}}'::jsonb,
    compliance_score  INT          NOT NULL DEFAULT 0,
    compliance_passed BOOLEAN      NOT NULL DEFAULT FALSE,
    review_status     VARCHAR(32)  NOT NULL DEFAULT '{STATUS_PENDING}',
    created_by        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by       VARCHAR(64),
    reviewed_at       TIMESTAMPTZ,
    review_comment    VARCHAR(512),
    published_at      TIMESTAMPTZ
);
"""

CREATE_MARKETING_TASK_INDEX_SQL = (
    f"CREATE INDEX IF NOT EXISTS idx_{MARKETING_TASK_TABLE}_tenant_created "
    f"ON {MARKETING_TASK_TABLE} (tenant_id, created_at DESC);"
)


async def init_marketing_task_table() -> None:
    """确保 ``t_marketing_task`` 表与索引就绪（幂等）。"""
    conn = await psycopg.AsyncConnection.connect(settings.database_url)
    try:
        async with conn.transaction():
            await conn.execute(CREATE_MARKETING_TASK_TABLE_SQL)
            await conn.execute(CREATE_MARKETING_TASK_INDEX_SQL)
    finally:
        await conn.close()


def init_marketing_task_table_sync() -> None:
    """同步初始化（供脚本/运维调用）。"""
    conn = psycopg.connect(settings.database_url)
    try:
        with conn.transaction():
            conn.execute(CREATE_MARKETING_TASK_TABLE_SQL)
            conn.execute(CREATE_MARKETING_TASK_INDEX_SQL)
    finally:
        conn.close()


__all__ = [
    "MARKETING_TASK_TABLE",
    "STATUS_APPROVED",
    "STATUS_PENDING",
    "STATUS_REJECTED",
    "CREATE_MARKETING_TASK_TABLE_SQL",
    "CREATE_MARKETING_TASK_INDEX_SQL",
    "init_marketing_task_table",
    "init_marketing_task_table_sync",
]
