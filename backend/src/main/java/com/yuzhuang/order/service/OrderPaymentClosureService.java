package com.yuzhuang.order.service;

import com.yuzhuang.order.dto.PaymentSandboxPayRequest;
import com.yuzhuang.order.dto.PaymentSandboxResponse;

/**
 * 订单支付与超时关单领域服务。
 *
 * <p>覆盖契约：
 * <ul>
 *   <li>{@link #sandboxPay}：支付成功入账（沙箱通道），订单 STOCK_CONFIRMED → PROCESSING
 *       （已支付、进入履约）；同 tradeNo 幂等返回，重复渠道支付 409 拒绝；</li>
 *   <li>{@link #closeExpiredOrders}：扫描超过支付时限仍未支付的 STOCK_CONFIRMED 订单，
 *       原子关闭（CANCELLED + closeReason=PAY_TIMEOUT）并按明细回补库存；状态门保证
 *       并发/多实例下只有一次成功，库存回补幂等。</li>
 * </ul>
 */
public interface OrderPaymentClosureService {

    /**
     * 支付成功入账（沙箱演示通道）。
     *
     * @param tenantId 租户标识（来自 JWT）
     * @param orderNo  业务订单号
     * @param request  渠道 + 流水号
     * @return 支付结果
     */
    PaymentSandboxResponse sandboxPay(String tenantId, String orderNo, PaymentSandboxPayRequest request);

    /**
     * 超时关单扫描：关闭超时未支付的 STOCK_CONFIRMED 订单并回补库存。
     *
     * @param limit 单批扫描上限
     * @return 实际关闭并回补库存的订单数
     */
    int closeExpiredOrders(int limit);
}
