package com.yuzhuang.web.filter;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪过滤器：
 * <ul>
 *   <li>从 {@code X-Request-Id} 读取链路 ID，缺失则生成 {@code req-<uuid>}；</li>
 *   <li>写入 SLF4J MDC（供日志/响应体 requestId 复用）；</li>
 *   <li>回写 {@code X-Request-Id} 响应头。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HeaderNames.X_REQUEST_ID);
        if (!StringUtils.hasText(requestId)) {
            requestId = TraceContext.generateRequestId();
        }
        TraceContext.setRequestId(requestId);
        response.setHeader(HeaderNames.X_REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
