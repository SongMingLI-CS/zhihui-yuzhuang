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
