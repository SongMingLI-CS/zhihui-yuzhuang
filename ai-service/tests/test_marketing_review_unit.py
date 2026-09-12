"""营销任务审批状态机单元测试（离线、无数据库）。"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.services.marketing_review import (  # noqa: E402
    ACTION_APPROVE,
    ACTION_PUBLISH,
    ACTION_REJECT,
    STATUS_APPROVED,
    STATUS_PENDING,
    STATUS_REJECTED,
    can_transition,
    transition_reason,
)


def test_pending_can_be_approved_or_rejected():
    assert can_transition(STATUS_PENDING, ACTION_APPROVE) is True
    assert can_transition(STATUS_PENDING, ACTION_REJECT) is True


def test_approved_cannot_be_re_approved_or_rejected():
    assert can_transition(STATUS_APPROVED, ACTION_APPROVE) is False
    assert can_transition(STATUS_APPROVED, ACTION_REJECT) is False


def test_only_approved_can_be_published():
    # 验收关键项：未经人工批准不得标记为已发布
    assert can_transition(STATUS_PENDING, ACTION_PUBLISH) is False
    assert can_transition(STATUS_REJECTED, ACTION_PUBLISH) is False
    assert can_transition(STATUS_APPROVED, ACTION_PUBLISH) is True


def test_unknown_action_rejected():
    assert can_transition(STATUS_PENDING, "delete") is False
    assert "未知操作" in transition_reason(STATUS_PENDING, "delete")


def test_transition_reason_messages():
    assert "已发布" in transition_reason(STATUS_PENDING, ACTION_PUBLISH)
    assert "重复审批" in transition_reason(STATUS_APPROVED, ACTION_APPROVE)
