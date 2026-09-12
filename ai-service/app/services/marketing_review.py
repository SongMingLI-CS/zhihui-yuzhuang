"""营销任务审批状态机（纯函数，便于离线单测）。

规则（阶段 F「未经人工批准不得标记为已发布」）：
- ``approve``：仅 PENDING_HUMAN_REVIEW → APPROVED；
- ``reject``：仅 PENDING_HUMAN_REVIEW → REJECTED；
- ``publish``：仅 APPROVED → 允许（写 published_at），否则拒绝。
"""

from __future__ import annotations

from app.db.marketing_tables import STATUS_APPROVED, STATUS_PENDING, STATUS_REJECTED

ACTION_APPROVE = "approve"
ACTION_REJECT = "reject"
ACTION_PUBLISH = "publish"

_ALLOWED: dict[str, set[str]] = {
    ACTION_APPROVE: {STATUS_PENDING},
    ACTION_REJECT: {STATUS_PENDING},
    ACTION_PUBLISH: {STATUS_APPROVED},
}


def can_transition(current_status: str, action: str) -> bool:
    """判断当前状态是否允许执行该动作。"""
    allowed = _ALLOWED.get(action)
    if allowed is None:
        return False
    return current_status in allowed


def transition_reason(current_status: str, action: str) -> str:
    """返回不允许时的可读原因（用于接口错误消息）。"""
    if action == ACTION_PUBLISH:
        return "仅审批通过（APPROVED）的任务可标记为已发布"
    if action in {ACTION_APPROVE, ACTION_REJECT}:
        return "该任务已完成审批，不能重复审批"
    return f"未知操作：{action}"


__all__ = [
    "ACTION_APPROVE",
    "ACTION_PUBLISH",
    "ACTION_REJECT",
    "can_transition",
    "transition_reason",
    "STATUS_APPROVED",
    "STATUS_PENDING",
    "STATUS_REJECTED",
]
