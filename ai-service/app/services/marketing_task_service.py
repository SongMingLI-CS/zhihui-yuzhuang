"""营销任务持久化与审批服务（阶段 F）。

职责：
- ``create``：生成完成后落库（输入/输出 JSON、合规快照、创建人）；
- ``list_tasks``：按租户（可选状态）分页查询，供 B 端审批台账；
- ``act``：审批（approve/reject）与发布（publish），严格走
  :mod:`app.services.marketing_review` 状态机 —— 未经人工批准不得标记为已发布。

租户与操作人一律由调用方从已验证身份传入，服务不信任请求体。
"""

from __future__ import annotations

import json
import logging
from typing import Optional

from app.db.marketing_tables import (
    MARKETING_TASK_TABLE,
    STATUS_APPROVED,
    STATUS_PENDING,
    STATUS_REJECTED,
    init_marketing_task_table,
)
from app.db.session import connection
from app.schemas.marketing import MarketingTaskItem, MarketingTaskPage
from app.services.marketing_review import (
    ACTION_APPROVE,
    ACTION_PUBLISH,
    ACTION_REJECT,
    can_transition,
    transition_reason,
)

logger = logging.getLogger(__name__)

_INSERT_TASK_SQL = f"""
INSERT INTO {MARKETING_TASK_TABLE}
    (tenant_id, product_name, request_payload, response_payload,
     compliance_score, compliance_passed, review_status, created_by, created_at)
VALUES (%(tenant_id)s, %(product_name)s, %(request_payload)s::jsonb, %(response_payload)s::jsonb,
        %(score)s, %(passed)s, '{STATUS_PENDING}', %(created_by)s, CURRENT_TIMESTAMP)
RETURNING id
"""

_LIST_SQL = f"""
SELECT id, tenant_id, product_name, compliance_score, compliance_passed, review_status,
       created_by, created_at, reviewed_by, reviewed_at, review_comment, published_at
FROM {MARKETING_TASK_TABLE}
WHERE tenant_id = %(tenant_id)s
  AND (%(status)s IS NULL OR review_status = %(status)s)
ORDER BY id DESC
LIMIT %(limit)s OFFSET %(offset)s
"""

_COUNT_SQL = f"""
SELECT COUNT(*)::int AS total FROM {MARKETING_TASK_TABLE}
WHERE tenant_id = %(tenant_id)s
  AND (%(status)s IS NULL OR review_status = %(status)s)
"""

_GET_SQL = f"""
SELECT id, tenant_id, product_name, compliance_score, compliance_passed, review_status,
       created_by, created_at, reviewed_by, reviewed_at, review_comment, published_at
FROM {MARKETING_TASK_TABLE}
WHERE id = %(id)s AND tenant_id = %(tenant_id)s
"""

_APPROVE_SQL = f"""
UPDATE {MARKETING_TASK_TABLE}
SET review_status = %(status)s, reviewed_by = %(actor)s, reviewed_at = CURRENT_TIMESTAMP,
    review_comment = %(comment)s
WHERE id = %(id)s AND tenant_id = %(tenant_id)s
"""

_PUBLISH_SQL = f"""
UPDATE {MARKETING_TASK_TABLE}
SET published_at = CURRENT_TIMESTAMP
WHERE id = %(id)s AND tenant_id = %(tenant_id)s AND review_status = '{STATUS_APPROVED}'
"""


class MarketingTaskError(Exception):
    """营销任务业务错误（消息直接返回调用方）。"""


def _iso(value) -> str:
    return value.isoformat() if value is not None else ""


def _to_item(row) -> MarketingTaskItem:
    return MarketingTaskItem(
        id=int(row["id"]),
        tenantId=str(row["tenant_id"]),
        productName=str(row["product_name"]),
        complianceScore=int(row["compliance_score"] or 0),
        compliancePassed=bool(row["compliance_passed"]),
        reviewStatus=str(row["review_status"]),
        createdBy=str(row["created_by"] or ""),
        createdAt=_iso(row["created_at"]),
        reviewedBy=str(row["reviewed_by"] or ""),
        reviewedAt=_iso(row["reviewed_at"]),
        reviewComment=str(row["review_comment"] or ""),
        publishedAt=_iso(row["published_at"]),
    )


class MarketingTaskService:
    """营销任务台账服务。"""

    async def create(
        self,
        *,
        tenant_id: str,
        product_name: str,
        request_payload: dict,
        response_payload: dict,
        compliance_score: int,
        compliance_passed: bool,
        created_by: str,
    ) -> int:
        """落库一次生成任务，返回任务 ID。"""
        await init_marketing_task_table()
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    _INSERT_TASK_SQL,
                    {
                        "tenant_id": tenant_id,
                        "product_name": product_name[:128],
                        "request_payload": json.dumps(request_payload, ensure_ascii=False),
                        "response_payload": json.dumps(response_payload, ensure_ascii=False),
                        "score": int(compliance_score),
                        "passed": bool(compliance_passed),
                        "created_by": created_by or None,
                    },
                )
                row = await cur.fetchone()
                task_id = int(row["id"])
        logger.info(
            "marketing task created id=%s tenant=%s product=%.40s score=%d",
            task_id, tenant_id, product_name, compliance_score,
        )
        return task_id

    async def list_tasks(
        self,
        *,
        tenant_id: str,
        status: Optional[str] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> MarketingTaskPage:
        """分页查询任务台账（可按状态过滤）。"""
        await init_marketing_task_table()
        page = max(1, page)
        page_size = min(max(1, page_size), 100)
        params = {
            "tenant_id": tenant_id,
            "status": status or None,
            "limit": page_size,
            "offset": (page - 1) * page_size,
        }
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(_COUNT_SQL, params)
                total_row = await cur.fetchone()
                total = int(total_row["total"]) if total_row else 0
                await cur.execute(_LIST_SQL, params)
                rows = await cur.fetchall()
        total_pages = (total + page_size - 1) // page_size
        return MarketingTaskPage(
            items=[_to_item(r) for r in rows],
            page=page,
            pageSize=page_size,
            total=total,
            totalPages=total_pages,
        )

    async def act(
        self,
        *,
        tenant_id: str,
        task_id: int,
        action: str,
        actor: str,
        comment: str = "",
    ) -> MarketingTaskItem:
        """执行审批/发布动作（严格状态机校验）。"""
        await init_marketing_task_table()
        async with connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(_GET_SQL, {"id": task_id, "tenant_id": tenant_id})
                row = await cur.fetchone()
                if row is None:
                    raise MarketingTaskError("任务不存在或不属于该租户")
                current = str(row["review_status"])
                if not can_transition(current, action):
                    raise MarketingTaskError(transition_reason(current, action))

                if action == ACTION_APPROVE:
                    await cur.execute(
                        _APPROVE_SQL,
                        {
                            "status": STATUS_APPROVED,
                            "actor": actor,
                            "comment": comment or None,
                            "id": task_id,
                            "tenant_id": tenant_id,
                        },
                    )
                elif action == ACTION_REJECT:
                    await cur.execute(
                        _APPROVE_SQL,
                        {
                            "status": STATUS_REJECTED,
                            "actor": actor,
                            "comment": comment or None,
                            "id": task_id,
                            "tenant_id": tenant_id,
                        },
                    )
                elif action == ACTION_PUBLISH:
                    await cur.execute(_PUBLISH_SQL, {"id": task_id, "tenant_id": tenant_id})

                await cur.execute(_GET_SQL, {"id": task_id, "tenant_id": tenant_id})
                updated = await cur.fetchone()

        logger.info(
            "marketing task action=%s id=%s tenant=%s actor=%s", action, task_id, tenant_id, actor
        )
        return _to_item(updated)


__all__ = ["MarketingTaskError", "MarketingTaskService"]

