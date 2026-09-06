package com.yuzhuang.auth.filter;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：从 {@code Authorization: Bearer <token>} 解析令牌并写入
 * {@link AuthContext}（线程级）。
 *
 * <p>语义：仅解析有效令牌并注入身份，<b>不强制拦截</b>——无令牌/无效令牌按匿名处理，
 * 避免破坏现有开放接口（下单/商品查询）；后续鉴权由业务层按需检查 {@link AuthContext}。
 * 顺序排在 {@code TenantContextFilter}(+2) 之后。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            try {
                AuthPrincipal principal = jwtService.parseToken(token);
                AuthContext.set(principal);
            } catch (IllegalArgumentException ex) {
                // 无效/过期令牌按匿名处理，不阻断请求
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }
}
