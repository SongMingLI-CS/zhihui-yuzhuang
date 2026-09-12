package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情响应（对齐 docs/api-spec.yaml OrderDetailResponse）：
 * 订单主信息 + 明细行 {@code items}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailResponse {

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

    /** 收货人手机号 */
    private String recipientPhone;

    /** 详细收货地址 */
    private String detailedAddress;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 承运商（发货后填写） */
    private String carrier;

    /** 物流单号（发货后填写） */
    private String trackingNo;

    /** 发货时间 */
    private LocalDateTime shippedAt;

    /** 取消原因（CANCELLED 时填写） */
    private String cancelReason;

    /** 订单明细行 */
    private List<OrderItemResponse> items;
}
