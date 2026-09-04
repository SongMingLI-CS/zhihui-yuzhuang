package com.yuzhuang.order.enums;

/**
 * 订单来源渠道（严格对齐 docs/api-spec.yaml OrderCheckoutRequest.orderSource 枚举）：
 * {@code H5_PRIVATE / DOUYIN / KUAISHOU / B2B_PORTAL}。
 *
 * <p>存储层以枚举名落库（t_order.order_source），读取时按名还原；
 * 非法值在 Jackson 反序列化阶段抛出 {@code HttpMessageNotReadableException}
 * 并由全局异常处理器统一转为 400 / A1001。
 */
public enum OrderSource {

    /** 自营 H5 私域渠道 */
    H5_PRIVATE,

    /** 抖音渠道 */
    DOUYIN,

    /** 快手渠道 */
    KUAISHOU,

    /** B2B 集采门户 */
    B2B_PORTAL
}
