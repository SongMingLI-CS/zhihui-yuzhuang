package com.yuzhuang.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪上下文（基于 SLF4J MDC）。
 *
 * <p>由 {@code TraceIdFilter} 在请求入口写入 requestId，并回写
 * {@code X-Request-Id} 响应头；业务代码通过 {@link #getRequestId()}
 * 获取当前链路 ID（无则临时生成，保证响应 body 的 requestId 不为空）。
 */
public final class TraceContext {

    /** MDC / 响应头统一使用的 Key 名 */
    public static final String MDC_REQUEST_ID = "requestId";

    private TraceContext() {
    }

    /** 生成标准链路 ID，形如 req-<uuid>（与 ai-service 约定一致）。 */
    public static String generateRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /**
     * 获取当前链路 ID：优先读 MDC；无则临时生成一条（不写入 MDC）。
     *
     * @return 非空 requestId
     */
    public static String getRequestId() {
        String requestId = MDC.get(MDC_REQUEST_ID);
        return (requestId == null || requestId.isBlank()) ? generateRequestId() : requestId;
    }

    /** 写入链路 ID 到 MDC；入参为空时等价于清除。 */
    public static void setRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            clear();
            return;
        }
        MDC.put(MDC_REQUEST_ID, requestId.trim());
    }

    /** 清除 MDC 中的 requestId（过滤器 finally 中调用，防止线程复用串号）。 */
    public static void clear() {
        MDC.remove(MDC_REQUEST_ID);
    }
}
