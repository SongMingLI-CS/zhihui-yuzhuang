package com.yuzhuang.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.dto.AuthLoginRequest;
import com.yuzhuang.auth.dto.AuthLoginResponse;
import com.yuzhuang.auth.security.JwtService;
import com.yuzhuang.auth.service.AuthService;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口 · 登录/JWT 签发与校验测试（test profile，H2）。
 *
 * <p>演示账号由 {@code AuthDataInitializer} 在上下文启动时幂等插入（admin/admin123，角色 VILLAGE）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_success_returnsTokenAndUser() {
        AuthLoginResponse resp = authService.login(new AuthLoginRequest("admin", "admin123"));
        assertThat(resp.getToken()).isNotBlank();
        assertThat(resp.getTokenType()).isEqualTo("Bearer");
        assertThat(resp.getExpiresIn()).isPositive();
        assertThat(resp.getUser().getUsername()).isEqualTo("admin");
        assertThat(resp.getUser().getRole()).isEqualTo("VILLAGE");
        assertThat(resp.getUser().getTenantId()).isEqualTo("tenant_yuzhuang_001");
    }

    @Test
    void login_wrongPassword_throwsA1002() {
        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("admin", "wrongpass")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void login_unknownUser_throwsA1002() {
        assertThatThrownBy(() -> authService.login(new AuthLoginRequest("nobody", "whatever1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void token_roundTrip_parsesClaims() {
        AuthPrincipal principal = AuthPrincipal.builder()
                .userId(1L).username("admin").tenantId("tenant_yuzhuang_001")
                .role("VILLAGE").displayName("于庄村委管理员").build();
        String token = jwtService.generateToken(principal);
        AuthPrincipal parsed = jwtService.parseToken(token);
        assertThat(parsed.getUsername()).isEqualTo("admin");
        assertThat(parsed.getTenantId()).isEqualTo("tenant_yuzhuang_001");
        assertThat(parsed.getRole()).isEqualTo("VILLAGE");
        assertThat(parsed.getDisplayName()).isEqualTo("于庄村委管理员");
    }

    @Test
    void token_tampered_throwsIllegalArgument() {
        AuthPrincipal principal = AuthPrincipal.builder()
                .userId(1L).username("admin").tenantId("tenant_yuzhuang_001")
                .role("VILLAGE").displayName("于庄村委管理员").build();
        String token = jwtService.generateToken(principal);
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> jwtService.parseToken(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginEndpoint_success() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.role").value("VILLAGE"));
    }

    @Test
    void loginEndpoint_wrongPassword_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "wrongpass"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void loginEndpoint_invalidParam_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "admin", "password", "123"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }
}

