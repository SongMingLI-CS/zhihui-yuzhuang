package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 特产下单响应（严格对齐 docs/api-spec.yaml OrderCheckoutResponse）：
 * {@code orderNo / totalAmount / status / expireTime}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCheckoutResponse {

    /** 业务订单号 */
    private String orderNo;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 订单状态（下单成功为 STOCK_CONFIRMED） */
    private String status;

    /** 待支付超时毫秒时间戳（下单时刻 + 支付超时时长） */
    private Long expireTime;

    /**
     * 本人订单查询凭证（仅下单响应返回一次，请妥善保存）。
     *
     * <p>后续查询须调用 {@code POST /api/v1/orders/guest/lookup} 并同时提供
     * {@code orderNo + queryToken}；服务端仅保存其哈希，无法反查。
     */
    private String queryToken;
}
