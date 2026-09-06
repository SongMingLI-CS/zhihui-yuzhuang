package com.yuzhuang.order.service;

import com.yuzhuang.order.dto.OrderSummaryResponse;

/**
 * 订单履约写服务（出库流水维度，双轴解耦于交易 {@code status}）。
 */
public interface OrderFulfillmentService {

    /**
     * 订单一键出库：履约状态 {@code READY → SHIPPED}。
     *
     * @param tenantId 租户标识（null/空白按 {@code global} 兜底）
     * @param orderNo  业务订单号
     * @return 推进后订单摘要
     * @throws com.yuzhuang.common.exception.BusinessException
     *         不存在/跨租户 → A1004；非 READY 或并发已出库 → B2003
     */
    OrderSummaryResponse shipOrder(String tenantId, String orderNo);

    /**
     * 拣货完成置为待出库：履约状态 {@code PICKING → READY}。
     *
     * @param tenantId 租户标识
     * @param orderNo  业务订单号
     * @return 推进后订单摘要
     * @throws com.yuzhuang.common.exception.BusinessException 不存在/跨租户 A1004；非 PICKING B2003
     */
    OrderSummaryResponse markReady(String tenantId, String orderNo);

    /**
     * 异常单恢复拣货：履约状态 {@code ABNORMAL → PICKING}。
     *
     * @param tenantId 租户标识
     * @param orderNo  业务订单号
     * @return 推进后订单摘要
     * @throws com.yuzhuang.common.exception.BusinessException 不存在/跨租户 A1004；非 ABNORMAL B2003
     */
    OrderSummaryResponse recoverFromAbnormal(String tenantId, String orderNo);
}