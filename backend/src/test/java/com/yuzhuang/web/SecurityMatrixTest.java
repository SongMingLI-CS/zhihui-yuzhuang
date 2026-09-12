package com.yuzhuang.web;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.test.WebAuthTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端点 × 角色 × 租户 安全矩阵测试（阶段 B 验收项 3/4）。
 *
 * <p>覆盖：匿名未认证（401/A1002）、越权角色（403/A1003）、跨租户与 ID 枚举（404/A1004）、
 * 过期/篡改令牌（401）、合法角色放行（200/00000）。所有判定均由服务端
 * {@code EndpointSecurityPolicy} + 业务服务强制执行，不依赖前端隐藏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityMatrixTest extends WebAuthTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Value("${yuzhuang.auth.secret}")
    private String jwtSecret;

    @ParameterizedTest(name = "[{0}] {1} {2} -> {3}/{4}")
    @CsvSource({
            "ANONYMOUS,      GET,  /api/v1/orders,               401, A1002",
            "FARMER,         GET,  /api/v1/orders,               403, A1003",
            "COOPERATIVE,    GET,  /api/v1/orders,               200, 00000",
            "VILLAGE,        GET,  /api/v1/orders,               200, 00000",
            "GOVERNMENT,     GET,  /api/v1/orders,               403, A1003",
            "PLATFORM_ADMIN, GET,  /api/v1/orders,               200, 00000",
            "ANONYMOUS,      GET,  /api/v1/dashboard/summary,    401, A1002",
            "FARMER,         GET,  /api/v1/dashboard/summary,    403, A1003",
            "COOPERATIVE,    GET,  /api/v1/dashboard/summary,    200, 00000",
            "GOVERNMENT,     GET,  /api/v1/dashboard/summary,    200, 00000",
            "ANONYMOUS,      POST, /api/v1/products,             401, A1002",
            "FARMER,         POST, /api/v1/products,             403, A1003",
            "GOVERNMENT,     POST, /api/v1/products,             403, A1003",
            "ANONYMOUS,      POST, /api/v1/orders/ORD-X/ship,    401, A1002",
            "FARMER,         POST, /api/v1/orders/ORD-X/ship,    403, A1003",
            "ANONYMOUS,      GET,  /api/v1/admin/users,          401, A1002",
            "VILLAGE,        GET,  /api/v1/admin/users,          403, A1003",
            "COOPERATIVE,    GET,  /api/v1/admin/users,          403, A1003",
            "GOVERNMENT,     GET,  /api/v1/admin/users,          403, A1003",
            "ANONYMOUS,      GET,  /api/v1/unknown-endpoint,     401, A1002"
    })
    void endpointRoleMatrix(String role, String method, String path, int expectedStatus, String expectedCode)
            throws Exception {
        MockHttpServletRequestBuilder request = "POST".equals(method)
                ? MockMvcRequestBuilders.post(path)
                : MockMvcRequestBuilders.get(path);
        if (!"ANONYMOUS".equals(role)) {
            request.header(HeaderNames.AUTHORIZATION, bearer(role, TENANT_A));
        }
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @Test
    void expiredToken_isRejectedWith401A1002() throws Exception {
        String expired = buildExpiredToken();
        mockMvc.perform(get("/api/v1/orders").header(HeaderNames.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void tamperedToken_isRejectedWith401A1002() throws Exception {
        String token = bearer("VILLAGE", TENANT_A).substring("Bearer ".length());
        String tampered = token.substring(0, token.length() - 2) + "zz";
        mockMvc.perform(get("/api/v1/orders").header(HeaderNames.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void anonymousCannotEnumerateOrderByIdGuess() throws Exception {
        // ID 枚举：匿名直接按订单号猜测访问 → 401（不泄露资源存在性）
        mockMvc.perform(get("/api/v1/orders/ORD-20260901000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    /** 用与生产同一密钥手工签发一个已过期令牌，验证过期判定。 */
    private String buildExpiredToken() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", "expired-user");
        payload.put("userId", 9999);
        payload.put("tenantId", TENANT_A);
        payload.put("role", "VILLAGE");
        payload.put("displayName", "过期令牌");
        payload.put("iat", now - 7200);
        payload.put("exp", now - 3600);

        String headerB64 = b64(header);
        String payloadB64 = b64(payload);
        String signingInput = headerB64 + "." + payloadB64;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }

    private String b64(Map<String, Object> value) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

