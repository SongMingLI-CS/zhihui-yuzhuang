package com.yuzhuang.common.context;

/**
 * 多租户线程上下文。
 *
 * <p>由 {@code TenantContextFilter} 从请求头 {@code X-Tenant-Id} 提取并写入，
 * 供业务层做行级数据隔离与日志打点；缺省回退到 {@code global}（与 ai-service 默认一致）。
 */
public final class TenantContext {

    /** 缺省租户标识 */
    public static final String DEFAULT_TENANT_ID = "global";

    /** MDC 中的租户 Key 名 */
    public static final String MDC_TENANT_ID = "tenantId";

    private static final ThreadLocal<String> HOLDER = ThreadLocal.withInitial(() -> DEFAULT_TENANT_ID);

    private TenantContext() {
    }

    /** 设置当前线程租户；空串/空白回退 {@code global}。 */
    public static void setTenantId(String tenantId) {
        HOLDER.set(normalizeTenantId(tenantId));
    }

    /** 规整租户标识：null/空白回退 {@code global}，否则去除首尾空白（永不返回 null）。 */
    public static String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 获取当前线程租户（永不返回 null）。 */
    public static String getTenantId() {
        return HOLDER.get();
    }

    /** 清理当前线程租户（过滤器 finally 中调用）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
