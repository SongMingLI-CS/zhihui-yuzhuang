package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表摘要响应（对齐 docs/api-spec.yaml OrderSummaryResponse）：
 * {@code orderNo / orderSource / totalAmount / status / recipientName / createdAt}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryResponse {

    /** 业务订单号 */
    private String orderNo;

    /** 订单渠道来源（H5_PRIVATE / DOUYIN / KUAISHOU / B2B_PORTAL） */
    private String orderSource;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 订单状态 */
    private String status;

    /** 履约状态（出库流水维度） */
    private String fulfillmentStatus;

    /** 收货人姓名 */
    private String recipientName;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 承运商（发货后填写） */
    private String carrier;

    /** 物流单号（发货后填写） */
    private String trackingNo;

    /** 发货时间 */
    private LocalDateTime shippedAt;
}
