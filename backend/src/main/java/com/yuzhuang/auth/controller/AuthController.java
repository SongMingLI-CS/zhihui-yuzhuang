package com.yuzhuang.auth.controller;

import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.service.AuthService;
import com.yuzhuang.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（严格对齐 docs/api-spec.yaml /auth/login）。
 */
@Slf4j
@Tag(name = "认证", description = "用户登录与 JWT 签发")
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "用户登录（用户名/密码换取 JWT）", security = {})
    @PostMapping("/auth/login")
    public ApiResponse<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        log.debug("[auth] login username={}", request.getUsername());
        return ApiResponse.success(authService.login(request));
    }
}
