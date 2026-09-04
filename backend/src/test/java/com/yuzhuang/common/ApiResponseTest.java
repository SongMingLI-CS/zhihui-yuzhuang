package com.yuzhuang.common;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.common.trace.TraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约基石单元测试：ApiResponse / ResultCode / BusinessException / 上下文。
 */
class ApiResponseTest {

    @Test
    void successWithData() {
        ApiResponse<String> resp = ApiResponse.success("hello");

        assertThat(resp.getCode()).isEqualTo("00000");
        assertThat(resp.getMessage()).isEqualTo("操作成功");
        assertThat(resp.getData()).isEqualTo("hello");
        assertThat(resp.getTimestamp()).isPositive();
        assertThat(resp.getRequestId()).isNotBlank().startsWith("req-");
    }

    @Test
    void successWithoutData() {
        ApiResponse<Void> resp = ApiResponse.success();

        assertThat(resp.getCode()).isEqualTo("00000");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void failByResultCode() {
        ApiResponse<Void> resp = ApiResponse.fail(ResultCode.INVENTORY_STOCK_OUT);

        assertThat(resp.getCode()).isEqualTo("B2001");
        assertThat(resp.getMessage()).contains("库存不足");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void failByCustomCodeAndMessage() {
        ApiResponse<Void> resp = ApiResponse.fail("A1001", "oops");

        assertThat(resp.getCode()).isEqualTo("A1001");
        assertThat(resp.getMessage()).isEqualTo("oops");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void resultCodeLookupAndRanges() {
        assertThat(ResultCode.SUCCESS.getCode()).isEqualTo("00000");
        assertThat(ResultCode.ofCode("B2001")).isEqualTo(ResultCode.INVENTORY_STOCK_OUT);
        assertThat(ResultCode.ofCode("A1001")).isEqualTo(ResultCode.PARAM_ERROR);
        assertThat(ResultCode.ofCode("C5001")).isEqualTo(ResultCode.SYSTEM_ERROR);
        assertThat(ResultCode.ofCode("unknown")).isNull();
        assertThat(ResultCode.ofCode(null)).isNull();
    }

    @Test
    void businessExceptionCarriesCode() {
        BusinessException ex = new BusinessException(ResultCode.INVENTORY_STOCK_OUT);

        assertThat(ex.getCode()).isEqualTo("B2001");
        assertThat(ex.getMessage()).contains("库存不足");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void businessExceptionWithCustomMessageKeepsCode() {
        BusinessException ex = new BusinessException(ResultCode.INVENTORY_STOCK_OUT, "仅剩1件");

        assertThat(ex.getCode()).isEqualTo("B2001");
        assertThat(ex.getMessage()).isEqualTo("仅剩1件");
    }

    @Test
    void tenantContextDefaultAndIsolation() {
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isEqualTo("global");

        TenantContext.setTenantId("tenant_yuzhuang_001");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant_yuzhuang_001");

        TenantContext.setTenantId("  ");
        assertThat(TenantContext.getTenantId()).isEqualTo("global");

        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isEqualTo("global");
    }

    @Test
    void traceRequestIdFormat() {
        assertThat(TraceContext.generateRequestId()).matches("req-[0-9a-f-]{36}");
        assertThat(TraceContext.getRequestId()).isNotBlank();
    }
}
