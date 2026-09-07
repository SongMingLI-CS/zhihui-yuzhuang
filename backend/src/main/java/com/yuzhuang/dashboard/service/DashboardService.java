package com.yuzhuang.dashboard.service;

import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;

/**
 * 经营大盘聚合服务（按租户数据域读取真实订单/明细，替换前端 demo 快照）。
 */
public interface DashboardService {

    /**
     * 汇总某租户经营大盘快照：合计 + 近 7 日趋势（空日补零）+ 商品销量 TOP N。
     *
     * @param tenantId 租户标识（来自 JWT）
     * @return 大盘聚合响应
     */
    DashboardSummaryResponse summary(String tenantId);
}
