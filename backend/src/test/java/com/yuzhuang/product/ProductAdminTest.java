package com.yuzhuang.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;
import com.yuzhuang.product.service.ProductAdminService;
import com.yuzhuang.test.WebAuthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
class ProductAdminTest extends WebAuthTestSupport {

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
                new ProductUpsertRequest("SKU-UPD", "新名", new BigDecimal("20.00"), 8, "ON_SALE"), TENANT_A);
        assertThat(resp.getSpuName()).isEqualTo("新名");
        assertThat(resp.getPrice()).isEqualByComparingTo("20.00");
        assertThat(resp.getStock()).isEqualTo(8);
    }

    @Test
    void updateProduct_notFound_throwsA1004() {
        ProductUpsertRequest upd = new ProductUpsertRequest("SKU-X", "x", new BigDecimal("10.00"), 1, "ON_SALE");
        assertThatThrownBy(() -> productAdminService.updateProduct(999999L, upd, TENANT_A))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void updateStatus_offShelf() {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-STAT", "状态", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        ProductSkuResponse resp = productAdminService.updateStatus(created.getId(), new ProductStatusRequest("OFF_SHELF"), TENANT_A);
        assertThat(resp.getStatus()).isEqualTo("OFF_SHELF");
    }

    @Test
    void createEndpoint_success() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("skuCode", "SKU-HTTP", "spuName", "HTTP商品", "price", 10.0, "stock", 5, "status", "ON_SALE"));
        mockMvc.perform(post("/api/v1/products")
                        .header(HeaderNames.AUTHORIZATION, bearer("VILLAGE", TENANT_A))
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
                        .header(HeaderNames.AUTHORIZATION, villageBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFF_SHELF"));
    }

    @Test
    void createEndpoint_invalidPrice_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("skuCode", "SKU-BAD", "spuName", "坏", "price", -1, "stock", 5));
        mockMvc.perform(post("/api/v1/products")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }

    // ============================================================
    // 安全强化回归：跨租户写修复 + 角色矩阵 + global 目录维护规则
    // ============================================================

    @Test
    void updateProduct_crossTenant_throwsA1004() {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-XT", "A租户商品", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        ProductUpsertRequest upd = new ProductUpsertRequest("SKU-XT", "越权改名", new BigDecimal("10.00"), 5, "ON_SALE");
        assertThatThrownBy(() -> productAdminService.updateProduct(created.getId(), upd, TENANT_B))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void updateProduct_villageMaintainsGlobalCatalog() {
        ProductSku global = seedSku("global", "SKU-GLB-1", "共享目录商品", "ON_SALE");
        AuthContext.set(principal("VILLAGE", TENANT_A));
        try {
            ProductSkuResponse resp = productAdminService.updateProduct(global.getId(),
                    new ProductUpsertRequest("SKU-GLB-1", "运营改名", new BigDecimal("20.00"), 9, "ON_SALE"), TENANT_A);
            assertThat(resp.getSpuName()).isEqualTo("运营改名");
            assertThat(resp.getTenantId()).isEqualTo("global");
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void updateProduct_nonVillageCannotMaintainGlobalCatalog() {
        ProductSku global = seedSku("global", "SKU-GLB-2", "共享目录商品2", "ON_SALE");
        AuthContext.set(principal("COOPERATIVE", TENANT_A));
        try {
            assertThatThrownBy(() -> productAdminService.updateProduct(global.getId(),
                    new ProductUpsertRequest("SKU-GLB-2", "合作社改名", new BigDecimal("20.00"), 9, "ON_SALE"), TENANT_A))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void updateStatusEndpoint_crossTenantToken_returns404() throws Exception {
        ProductSkuResponse created = productAdminService.createProduct(
                new ProductUpsertRequest("SKU-HTTP-XT", "他租户商品", new BigDecimal("10.00"), 5, "ON_SALE"), TENANT_A);
        String body = objectMapper.writeValueAsString(Map.of("status", "OFF_SHELF"));
        mockMvc.perform(patch("/api/v1/products/" + created.getId() + "/status")
                        .header(HeaderNames.AUTHORIZATION, bearer("COOPERATIVE", TENANT_B))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));
    }

    @Test
    void createEndpoint_farmerForbidden_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("skuCode", "SKU-FM", "spuName", "农户商品", "price", 10.0, "stock", 5, "status", "ON_SALE"));
        mockMvc.perform(post("/api/v1/products")
                        .header(HeaderNames.AUTHORIZATION, farmerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    private ProductSku seedSku(String tenantId, String skuCode, String spuName, String status) {
        ProductSku sku = ProductSku.builder()
                .tenantId(tenantId)
                .skuCode(skuCode)
                .spuName(spuName)
                .price(new BigDecimal("10.00"))
                .stock(100)
                .version(0)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }
}

