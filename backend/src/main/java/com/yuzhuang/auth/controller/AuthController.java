package com.yuzhuang.auth.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.dto.ChangePasswordRequest;
import com.yuzhuang.auth.dto.UserInfoResponse;
import com.yuzhuang.auth.security.SessionCookieService;
import com.yuzhuang.auth.service.AuthService;
import com.yuzhuang.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（严格对齐 docs/api-spec.yaml /auth/*）。
 *
 * <p>会话模式（阶段 A P0-3）：登录成功同时写入 {@code HttpOnly+Secure+SameSite} 会话 Cookie
 * 与可读 CSRF Cookie；浏览器后续请求凭 Cookie 认证，写接口需回传 {@code X-CSRF-Token}。
 * 令牌仍随响应返回 {@code token} 字段，兼容 H5 / 移动端 Bearer 模式。
 */
@Slf4j
@Tag(name = "认证", description = "用户登录、会话、改密")
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;
    private final SessionCookieService sessionCookieService;

    public AuthController(AuthService authService, SessionCookieService sessionCookieService) {
        this.authService = authService;
        this.sessionCookieService = sessionCookieService;
    }

    @Operation(summary = "用户登录（用户名/密码换取 JWT + 会话 Cookie）", security = {})
    @PostMapping("/auth/login")
    public ApiResponse<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request,
                                                HttpServletResponse response) {
        log.debug("[auth] login username={}", request.getUsername());
        AuthLoginResponse result = authService.login(request);
        sessionCookieService.issueSessionCookie(response, result.getToken(), result.getExpiresIn());
        sessionCookieService.issueCsrfCookie(response);
        return ApiResponse.success(result);
    }

    @Operation(summary = "登出（清除会话 Cookie）", security = {})
    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletResponse response) {
        sessionCookieService.clearSessionCookie(response);
        return ApiResponse.success(Map.of("loggedOut", true));
    }

    @Operation(summary = "获取/刷新 CSRF 令牌（双提交 Cookie）", security = {})
    @GetMapping("/auth/csrf")
    public ApiResponse<Map<String, String>> csrf(HttpServletResponse response) {
        String token = sessionCookieService.issueCsrfCookie(response);
        return ApiResponse.success(Map.of("csrfToken", token,
                "headerName", sessionCookieService.getCsrfHeaderName()));
    }

    @Operation(summary = "当前登录账号信息（会话恢复）")
    @GetMapping("/auth/me")
    public ApiResponse<UserInfoResponse> me() {
        return ApiResponse.success(authService.me(AuthContext.require()));
    }

    @Operation(summary = "修改密码（首次登录强制改密）")
    @PostMapping("/auth/password/change")
    public ApiResponse<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(AuthContext.require(), request);
        return ApiResponse.success(Map.of("changed", true));
    }
}

