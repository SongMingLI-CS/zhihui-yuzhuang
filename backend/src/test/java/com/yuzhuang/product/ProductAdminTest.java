package com.yuzhuang.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;
import com.yuzhuang.product.service.ProductAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品管理写接口 · 上架/编辑/上下架测试（test profile，H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductAdminTest {

    private static final String TENANT_A = "tenant_yuzhuang_001";

    @Autowired
    private ProductAdminService productAdminService;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanSkuTable() {
        TenantContext.clear();
        productSkuMapper.delete(null);
    }

    @Test
    void createProduct_success() {
        ProductUpsertRequest req = new ProductUpsertRequest("SKU-NEW", "于庄新品", new BigDecimal("99.00"), 10, "ON_SALE");
        ProductSkuResponse resp = productAdminService.createProduct(req, TENANT_A);
        assertThat(resp.getId()).isNotNull();
        assertThat(resp.getSkuCode()).isEqualTo("SKU-NEW");
        assertThat(resp.getStatus()).isEqualTo("ON_SALE");
        assertThat(resp.getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void createProduct_defaultStatusAndTenant() {
        ProductUpsertRequest req = new ProductUpsertRequest("SKU-DEF", "于庄默认", new BigDecimal("10.00"), 5, null);
        ProductSkuResponse resp = productAdminService.createProduct(req, null);
        assertThat(resp.getStatus()).isEqualTo("ON_SALE");
        assertThat(resp.getTenantId()).isEqualTo("global");
    }

    @Test
    void createProduct_duplicateSkuCode_throwsB2004() {
        ProductUpsertRequest first = new ProductUpsertRequest("SKU-DUP", "重复", new BigDecimal("10.00"), 5, null);
        productAdminService.createProduct(first, TENANT_A);
        assertThatThrownBy(() -> productAdminService.createProduct(
                new ProductUpsertRequest("SKU-DUP", "重复2", new BigDecimal("10.00"), 5, null), TENANT_A))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.SKU_CODE_CONFLICT.getCode()));
    }

    @Test
    void updateProduct_success() {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-UPD", "原名", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        ProductSkuResponse resp = productAdminService.updateProduct(created.getId(),
                new ProductUpsertRequest("SKU-UPD", "新名", new BigDecimal("20.00"), 8, "ON_SALE"));
        assertThat(resp.getSpuName()).isEqualTo("新名");
        assertThat(resp.getPrice()).isEqualByComparingTo("20.00");
        assertThat(resp.getStock()).isEqualTo(8);
    }

    @Test
    void updateProduct_notFound_throwsA1004() {
        ProductUpsertRequest upd = new ProductUpsertRequest("SKU-X", "x", new BigDecimal("10.00"), 1, "ON_SALE");
        assertThatThrownBy(() -> productAdminService.updateProduct(999999L, upd))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void updateStatus_offShelf() {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-STAT", "状态", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        ProductSkuResponse resp = productAdminService.updateStatus(created.getId(), new ProductStatusRequest("OFF_SHELF"));
        assertThat(resp.getStatus()).isEqualTo("OFF_SHELF");
    }

    @Test
    void createEndpoint_success() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("skuCode", "SKU-HTTP", "spuName", "HTTP商品", "price", 10.0, "stock", 5, "status", "ON_SALE"));
        mockMvc.perform(post("/api/v1/products")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.skuCode").value("SKU-HTTP"));
    }

    @Test
    void updateStatusEndpoint_success() throws Exception {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-HTTP2", "HTTP2", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        String body = objectMapper.writeValueAsString(Map.of("status", "OFF_SHELF"));
        mockMvc.perform(patch("/api/v1/products/" + created.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFF_SHELF"));
    }

    @Test
    void createEndpoint_invalidPrice_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("skuCode", "SKU-BAD", "spuName", "坏", "price", -1, "stock", 5));
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }
}

