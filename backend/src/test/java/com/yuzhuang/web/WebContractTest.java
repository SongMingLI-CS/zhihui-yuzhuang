package com.yuzhuang.web;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
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
class WebContractTest {

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
