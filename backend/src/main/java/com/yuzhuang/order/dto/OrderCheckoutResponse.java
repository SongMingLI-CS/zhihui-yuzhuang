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
}
