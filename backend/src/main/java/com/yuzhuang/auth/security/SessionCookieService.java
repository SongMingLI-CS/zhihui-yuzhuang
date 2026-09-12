package com.yuzhuang.auth.security;

import com.yuzhuang.auth.filter.CookieNames;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 会话 / CSRF Cookie 服务。
 *
 * <p>安全目标（阶段 A P0-3）：B 端浏览器会话优先使用 {@code HttpOnly + Secure + SameSite}
 * Cookie，避免 JWT 长期暴露在 localStorage；写接口配合“双提交 CSRF Cookie + 请求头”防护。
 * 令牌仍同时返回给调用方，兼容 H5 / 移动端等非浏览器客户端（Bearer 头）。
 */
@Component
public class SessionCookieService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean sessionEnabled;
    private final String sessionCookieName;
    private final boolean secure;
    private final String sameSite;
    private final String domain;

    private final boolean csrfEnabled;
    private final String csrfHeaderName;
    private final String csrfCookieName;

    public SessionCookieService(
            @Value("${yuzhuang.auth.session-cookie.enabled:true}") boolean sessionEnabled,
            @Value("${yuzhuang.auth.session-cookie.name:yz_session}") String sessionCookieName,
            @Value("${yuzhuang.auth.session-cookie.secure:false}") boolean secure,
            @Value("${yuzhuang.auth.session-cookie.same-site:Lax}") String sameSite,
            @Value("${yuzhuang.auth.session-cookie.domain:}") String domain,
            @Value("${yuzhuang.auth.csrf.enabled:true}") boolean csrfEnabled,
            @Value("${yuzhuang.auth.csrf.header-name:X-CSRF-Token}") String csrfHeaderName,
            @Value("${yuzhuang.auth.csrf.cookie-name:yz_csrf}") String csrfCookieName) {
        this.sessionEnabled = sessionEnabled;
        this.sessionCookieName = sessionCookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.domain = domain;
        this.csrfEnabled = csrfEnabled;
        this.csrfHeaderName = csrfHeaderName;
        this.csrfCookieName = csrfCookieName;
    }

    public boolean isSessionCookieEnabled() {
        return sessionEnabled;
    }

    public boolean isCsrfEnabled() {
        return csrfEnabled;
    }

    public String getCsrfHeaderName() {
        return csrfHeaderName;
    }

    public String getSessionCookieName() {
        return sessionCookieName;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    /** 写登录会话 Cookie（HttpOnly，浏览器 JS 不可读）。 */
    public void issueSessionCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        if (!sessionEnabled) {
            return;
        }
        ResponseCookie cookie = builder(sessionCookieName, token)
                .httpOnly(true)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 清除会话 Cookie（登出）。 */
    public void clearSessionCookie(HttpServletResponse response) {
        ResponseCookie cookie = builder(sessionCookieName, "")
                .httpOnly(true)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 下发 CSRF 令牌 Cookie（非 HttpOnly，供前端读取后以请求头回传，双提交校验）。
     *
     * @return 生成的 CSRF 令牌
     */
    public String issueCsrfCookie(HttpServletResponse response) {
        String token = newCsrfToken();
        ResponseCookie cookie = builder(csrfCookieName, token)
                .httpOnly(false)
                .maxAge(Duration.ofHours(8))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return token;
    }

    /** 读取会话 Cookie 值（无则 null）。 */
    public String readSessionCookie(HttpServletRequest request) {
        return readCookie(request, sessionCookieName);
    }

    /** 读取 CSRF Cookie 值（无则 null）。 */
    public String readCsrfCookie(HttpServletRequest request) {
        return readCookie(request, csrfCookieName);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String newCsrfToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie.ResponseCookieBuilder builder(String name, String value) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .path("/")
                .secure(secure)
                .sameSite(sameSite);
        if (domain != null && !domain.isBlank()) {
            b.domain(domain);
        }
        return b;
    }
}
