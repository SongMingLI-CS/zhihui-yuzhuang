package com.yuzhuang.common.constant;

/**
 * 契约请求头常量（对齐 docs/api-spec.yaml）。
 */
public final class HeaderNames {

    /** 链路追踪 ID（可选，服务端缺失时自动生成 req-<uuid>） */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /** 多租户标识（合作社/行政村），必填示例 tenant_yuzhuang_001 */
    public static final String X_TENANT_ID = "X-Tenant-Id";

    /** 认证令牌头（Bearer <JWT>）。受保护端点从令牌解析租户与角色，X-Tenant-Id 不再作为受保护端点租户源。 */
    public static final String AUTHORIZATION = "Authorization";

    /** 幂等键（下单等写接口必填，防止秒杀重复点击） */
    public static final String X_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    private HeaderNames() {
    }
}
