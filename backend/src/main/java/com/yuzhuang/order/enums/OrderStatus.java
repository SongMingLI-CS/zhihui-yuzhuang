package com.yuzhuang.order.enums;

/**
 * 订单生命周期状态（覆盖 docs/api-spec.yaml OrderCheckoutResponse.status 枚举）。
 *
 * <p>下单交易闭环在库存扣减成功后即以 {@link #STOCK_CONFIRMED} 落库，
 * 表明库存已锁定待支付；后续由支付/履约流程推进到其余状态。
 */
public enum OrderStatus {

    /** 待支付 */
    PENDING_PAY,

    /** 库存已锁定确认（下单成功、等待支付超时） */
    STOCK_CONFIRMED,

    /** 处理中（已支付，仓库履约中） */
    PROCESSING,

    /** 已取消/超时关闭 */
    CANCELLED,

    /** 已完成 */
    COMPLETED
}
