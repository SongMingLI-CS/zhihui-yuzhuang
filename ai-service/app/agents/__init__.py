"""多 Agent 节点包：特产营销协同生成。

- ``app.agents.marketing_agents.TrendAgent``       热点/文化切入点提炼
- ``app.agents.marketing_agents.CopywriterAgent``  分渠道差异化文案生成
- ``app.agents.marketing_agents.ComplianceAgent``  广告法合规二次质检
"""

from app.agents.marketing_agents import (
    ComplianceAgent,
    CopywriterAgent,
    TrendAgent,
)

__all__ = [
    "TrendAgent",
    "CopywriterAgent",
    "ComplianceAgent",
]
