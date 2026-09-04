package com.yuzhuang.order.service;

import com.yuzhuang.order.dto.OrderCheckoutRequest;
import com.yuzhuang.order.dto.OrderCheckoutResponse;

/**
 * 特产下单交易服务。
 */
public interface OrderService {

    /**
     * 特产下单交易闭环（CAS 扣库存 + 订单/明细落库 + Outbox 事件同事务）。
     *
     * <p>幂等语义：
     * <ul>
     *   <li>同一 {@code idempotencyKey} 已存在有效订单 → 直接回放返回该订单（不重复扣库存/发事件）；</li>
     *   <li>并发同键恰好同时通过前置检查 → 由数据库 (tenant_id, idempotency_key) 唯一索引拦截，
     *       落败方收到 {@code ResultCode.IDEMPOTENT_CONFLICT} 并整体回滚（含库存扣减）。</li>
     * </ul>
     *
     * @param request        下单请求
     * @param idempotencyKey 幂等键（必填，防重复点击）
     * @return 下单结果（orderNo / totalAmount / status / expireTime）
     */
    OrderCheckoutResponse checkout(OrderCheckoutRequest request, String idempotencyKey);
}
