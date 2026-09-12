package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 匿名本人订单查询响应（阶段 E，已脱敏）。
 *
 * <p>仅返回履约所需的最小信息：状态、履约状态、金额、物流与脱敏后的收货人/手机号；
 * 不返回完整地址与完整手机号（隐私保护）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestOrderLookupResponse {

    /** 业务订单号 */
    private String orderNo;

    /** 订单状态 */
    private String status;

    /** 履约状态 */
    private String fulfillmentStatus;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 脱敏收货人（如 张*） */
    private String recipientNameMasked;

    /** 脱敏手机号（如 138****0000） */
    private String recipientPhoneMasked;

    /** 收货地址（仅到区/镇级别，不含门牌） */
    private String addressMasked;

    /** 承运商 */
    private String carrier;

    /** 物流单号 */
    private String trackingNo;

    /** 发货时间（epoch 毫秒，未发货为 null） */
    private Long shippedAt;

    /** 取消原因（已取消时返回） */
    private String cancelReason;

    /** 下单时间（epoch 毫秒） */
    private Long createdAt;
}
