package com.yuzhuang.auth.enums;

/**
 * 用户角色（对齐 docs/api-spec.yaml AuthLoginResponse.user.role 枚举）。
 *
 * <p>数据范围语义（服务端强制，见 {@code AuthGuardInterceptor} 与会话服务）：
 * <ul>
 *   <li>{@link #PLATFORM_ADMIN}：平台管理员。全平台租户/账号/角色/配置，强审计；不参与日常商品操作。</li>
 *   <li>{@link #GOVERNMENT}：政府。按 {@code t_gov_scope} 授权区域/租户集合，<b>只读</b>聚合、脱敏下钻与导出。</li>
 *   <li>{@link #VILLAGE}：村委。本村租户治理看板、知识审核、协同账号。</li>
 *   <li>{@link #COOPERATIVE}：合作社/商家。本租户商品、媒体、库存、订单、物流、营销。</li>
 *   <li>{@link #FARMER}：农户。本人/被分配任务与履约操作，不可读全租户经营与客户数据。</li>
 *   <li>{@link #CONSUMER}：消费者（可选）。仅本人订单/地址/支付/售后。</li>
 * </ul>
 */
public enum UserRole {

    /** 平台管理员（租户/账号/角色/配置，不参与日常商品操作） */
    PLATFORM_ADMIN,

    /** 政府（授权区域只读治理与导出） */
    GOVERNMENT,

    /** 农户 */
    FARMER,

    /** 合作社 / 商家 */
    COOPERATIVE,

    /** 村委 / 运营 */
    VILLAGE,

    /** 消费者（可选，仅本人订单） */
    CONSUMER
}

