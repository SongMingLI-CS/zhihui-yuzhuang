package com.yuzhuang.web;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.test.WebAuthTestSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 契约端到端测试（test profile，H2 内存库）：
 * 覆盖健康检查、链路/租户过滤器、全局异常处理、参数校验契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebContractTest extends WebAuthTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthzReturnsUpAndEchoesTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/healthz").header(HeaderNames.X_REQUEST_ID, "req-health-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(HeaderNames.X_REQUEST_ID, "req-health-001"))
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.db").value("UP"))
                .andExpect(jsonPath("$.requestId").value("req-health-001"));
    }

    @Test
    void businessExceptionMapsTo409B2001() throws Exception {
        mockMvc.perform(get("/probe/biz").header(HeaderNames.X_REQUEST_ID, "req-biz-001"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HeaderNames.X_REQUEST_ID, "req-biz-001"))
                .andExpect(jsonPath("$.code").value("B2001"))
                .andExpect(jsonPath("$.message").value("抱歉，该批次特产库存不足"))
                .andExpect(jsonPath("$.requestId").value("req-biz-001"));
    }

    @Test
    void unknownErrorMapsTo500C5001() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C5001"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void validationErrorMapsTo400A1001() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"))
                .andExpect(jsonPath("$.message").value(containsString("姓名不能为空")));
    }

    @Test
    void tenantHeaderCapturedIntoContext() throws Exception {
        mockMvc.perform(get("/probe/tenant").header(HeaderNames.X_TENANT_ID, "tenant_yuzhuang_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").value("tenant_yuzhuang_001"));
    }

    @Test
    void tenantDefaultsToGlobalWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/probe/tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").value("global"));
    }

    // ============================================================
    // 安全强化契约：认证/授权强制（401 A1002 / 403 A1003）+ 公开白名单
    // ============================================================

    @Test
    void protectedRead_withoutToken_returns401A1002() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void protectedWrite_withoutToken_returns401A1002() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"SKU-NO-AUTH\",\"spuName\":\"未授权商品\",\"price\":10.0,\"stock\":1,\"status\":\"ON_SALE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void protectedRead_invalidToken_returns401A1002() throws Exception {
        mockMvc.perform(get("/api/v1/orders").header(HeaderNames.AUTHORIZATION, "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
    }

    @Test
    void farmerCanReadOwnTenantOrders_butCannotShip() throws Exception {
        // 只读放行（任意已认证角色）→ 200 00000（数据域=令牌 tenantId，行数不在此断言）
        mockMvc.perform(get("/api/v1/orders").header(HeaderNames.AUTHORIZATION, farmerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 履约写需 COOPERATIVE/VILLAGE → 403 A1003
        mockMvc.perform(post("/api/v1/orders/ORD-NO-SUCH/ship")
                        .header(HeaderNames.AUTHORIZATION, farmerBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    @Test
    void publicAllowlist_remainsOpen() throws Exception {
        // 商品读：匿名可访问
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
        // 健康探针：匿名可访问（已在首测断言，这里确认未被拦截）
        mockMvc.perform(get("/api/v1/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /** 注册探针控制器（仅测试上下文使用，验证全局异常/租户/校验契约）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/biz")
        public void bizError() {
            throw new BusinessException(ResultCode.INVENTORY_STOCK_OUT);
        }

        @GetMapping("/probe/boom")
        public void systemError() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/probe/tenant")
        public ApiResponse<String> tenant() {
            return ApiResponse.success(TenantContext.getTenantId());
        }

        @PostMapping("/probe/validate")
        public void validate(@Valid @RequestBody ProbeRequest request) {
            // 交由全局异常处理器校验
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class ProbeRequest {

        @NotBlank(message = "姓名不能为空")
        private String name;
    }
}
