package com.yuzhuang.auth.filter;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * CSRF 双提交防护（仅对基于 Cookie 的浏览器会话生效）。
 *
 * <p>规则（阶段 A P0-3）：
 * <ul>
 *   <li>仅作用于 {@code /api/v1/**} 的非安全方法（POST/PUT/PATCH/DELETE）；</li>
 *   <li>仅当请求携带会话 Cookie（说明使用浏览器 Cookie 认证）时校验；纯 Bearer 客户端不受影响；</li>
 *   <li>要求请求头 {@code X-CSRF-Token} 与 CSRF Cookie 值常量时间相等，否则 403 + A1003；</li>
 *   <li>登录/登出端点豁免（登录时尚无会话；登出为幂等且无副作用）。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/csrf");

    private final SessionCookieService sessionCookieService;

    public CsrfProtectionFilter(SessionCookieService sessionCookieService) {
        this.sessionCookieService = sessionCookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!sessionCookieService.isCsrfEnabled()
                || !path.startsWith("/api/v1/")
                || !UNSAFE_METHODS.contains(request.getMethod().toUpperCase())
                || EXEMPT_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 仅对携带会话 Cookie 的浏览器请求强制 CSRF；Bearer 客户端天然不受 CSRF 影响。
        String sessionCookie = sessionCookieService.readSessionCookie(request);
        if (sessionCookie == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String cookieToken = sessionCookieService.readCsrfCookie(request);
        String headerToken = request.getHeader(sessionCookieService.getCsrfHeaderName());
        if (cookieToken == null || headerToken == null || !constantTimeEquals(cookieToken, headerToken)) {
            writeForbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"A1003\",\"message\":\"CSRF 校验失败，请刷新页面后重试\",\"data\":null}");
    }
}
