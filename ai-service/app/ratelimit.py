"""轻量进程内限流（匿名问答等公共端点的滥用防护）。

说明：单实例内存滑动窗口计数，足以抵御脚本刷取；多实例部署时应替换为
Redis 计数（项目已具备 Redis 底座）。此处不引入外部依赖，保证离线可测。
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque
from typing import Deque, Dict, Tuple


class SlidingWindowLimiter:
    """按 key 的滑动窗口限流器（线程安全）。"""

    def __init__(self, max_requests: int, window_seconds: float) -> None:
        self._max = max(1, max_requests)
        self._window = max(0.1, window_seconds)
        self._events: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> Tuple[bool, int]:
        """尝试消费一次配额；返回 ``(是否允许, 剩余配额)``。"""
        now = time.monotonic()
        with self._lock:
            bucket = self._events[key]
            cutoff = now - self._window
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= self._max:
                return False, 0
            bucket.append(now)
            return True, self._max - len(bucket)

    def reset(self) -> None:
        """清空全部计数（测试用）。"""
        with self._lock:
            self._events.clear()


# 匿名农技问答：每 IP 每分钟 20 次（生产可经环境变量覆盖）
anonymous_qa_limiter = SlidingWindowLimiter(max_requests=20, window_seconds=60.0)
