package com.yuzhuang.web.filter;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户上下文过滤器：从 {@code X-Tenant-Id} 提取租户标识写入
 * {@link TenantContext}（线程级）并同步到 MDC 用于日志打点；
 * 缺省回退 {@code global}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContext.setTenantId(request.getHeader(HeaderNames.X_TENANT_ID));
        MDC.put(TenantContext.MDC_TENANT_ID, TenantContext.getTenantId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(TenantContext.MDC_TENANT_ID);
        }
    }
}
