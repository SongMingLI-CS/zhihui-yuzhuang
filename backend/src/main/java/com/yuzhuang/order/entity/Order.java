package com.yuzhuang.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.enums.FulfillmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主订单实体（对应 t_order）。
 *
 * <p>同一 (tenant_id, idempotency_key) 由数据库唯一索引兜底防重；
 * {@code orderNo} 业务订单号亦唯一。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order")
public class Order {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 多租户标识 */
    private String tenantId;

    /** 业务订单号 */
    private String orderNo;

    /** 幂等键（与 tenant_id 联合唯一） */
    private String idempotencyKey;

    /** 订单渠道来源 */
    private OrderSource orderSource;

    /** 订单总金额（元） */
    private BigDecimal totalAmount;

    /** 订单状态 */
    private OrderStatus status;

    /** 履约状态（出库流水维度） */
    private FulfillmentStatus fulfillmentStatus;

    /** 支付渠道（沙箱 SANDBOX / 后续真实渠道适配） */
    private String payChannel;

    /** 渠道支付流水号（支付幂等去重键） */
    private String payTradeNo;

    /** 支付成功时间 */
    private LocalDateTime paidAt;

    /** 取消/超时关单时间 */
    private LocalDateTime cancelledAt;

    /** 关闭原因：PAY_TIMEOUT / MANUAL_CANCEL 等 */
    private String closeReason;

    /** 承运商（发货时填写） */
    private String carrier;

    /** 物流单号（发货时填写） */
    private String trackingNo;

    /** 发货时间 */
    private LocalDateTime shippedAt;

    /** 取消原因补充说明（用户取消/超时关单） */
    private String cancelReason;

    /** 本人订单查询凭证哈希（下单时下发一次性凭证，库中只存哈希） */
    private String queryTokenHash;

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
}
