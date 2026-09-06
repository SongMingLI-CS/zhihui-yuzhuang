package com.yuzhuang.order.enums;

/**
 * 订单履约状态（出库流水维度，与交易状态 {@link OrderStatus} 分离）。
 *
 * <p>流转：{@code PENDING → PICKING → READY → SHIPPED}；异常单标记 {@code ABNORMAL}。
 * B 端「订单与出库流水」看板据此展示履约进度与待出库队列。
 */
public enum FulfillmentStatus {

    /** 待履约（下单后默认） */
    PENDING,

    /** 拣货中 */
    PICKING,

    /** 出库就绪（可一键出库） */
    READY,

    /** 已出库 */
    SHIPPED,

    /** 异常（需人工介入） */
    ABNORMAL
}
