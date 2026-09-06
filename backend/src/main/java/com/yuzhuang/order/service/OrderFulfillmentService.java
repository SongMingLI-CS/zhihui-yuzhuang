package com.yuzhuang.order.service;

import com.yuzhuang.order.dto.OrderSummaryResponse;

/**
 * 订单履约写服务（出库流水维度）。
 */
public interface OrderFulfillmentService {

    /**
     * 订单一键出库：履约状态 {@code READY → SHIPPED}。
     *
     * @param tenantId 租户标识（null/空白按 {@code global} 兜底）
     * @param orderNo  业务订单号
     * @return 出库后订单摘要
     * @throws com.yuzhuang.common.exception.BusinessException
     *         不存在/跨租户 → A1004；非 READY 或并发已出库 → B2003
     */
    OrderSummaryResponse shipOrder(String tenantId, String orderNo);
}