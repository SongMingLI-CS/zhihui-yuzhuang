package com.yuzhuang.auth.filter;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.security.JwtService;
import com.yuzhuang.auth.security.SessionCookieService;
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
 * JWT 认证过滤器：从 {@code Authorization: Bearer <token>} 或会话 Cookie
 * （{@link SessionCookieService#readSessionCookie}）解析令牌并写入 {@link AuthContext}（线程级）。
 *
 * <p>语义：仅解析有效令牌并注入身份，<b>不强制拦截</b>——无令牌/无效令牌按匿名处理，
 * 避免破坏现有开放接口（下单/商品查询）；后续鉴权由 {@link com.yuzhuang.web.security.AuthGuardInterceptor}
 * 按端点策略强制。顺序排在 {@code TenantContextFilter}(+2) 之后。
 *
 * <p>优先级：Authorization 头 > 会话 Cookie（兼容 H5 / 移动端 Bearer 与浏览器 Cookie 两种模式）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionCookieService sessionCookieService;

    public JwtAuthenticationFilter(JwtService jwtService, SessionCookieService sessionCookieService) {
        this.jwtService = jwtService;
        this.sessionCookieService = sessionCookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
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

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return sessionCookieService.readSessionCookie(request);
    }
}

