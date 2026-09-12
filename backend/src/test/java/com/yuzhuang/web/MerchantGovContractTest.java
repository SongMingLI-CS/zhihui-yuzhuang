package com.yuzhuang.web;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 C/D 端点契约测试：商家工作台商品查询（含下架可见）+ 政府只读聚合 + 平台管理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantGovContractTest extends WebAuthTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProductSkuMapper productSkuMapper;

    @BeforeEach
    void cleanProducts() {
        productSkuMapper.delete(null);
    }

    @Test
    void merchantList_includesOffShelfAndDraft() throws Exception {
        seed("SKU-M-1", "在售商品", TENANT_A, "ON_SALE");
        seed("SKU-M-2", "下架商品", TENANT_A, "OFF_SHELF");
        seed("SKU-M-3", "草稿商品", TENANT_A, "DRAFT");

        // 商家可见全部状态（含下架，修复 P1-1 “下架即消失”）
        mockMvc.perform(get("/api/v1/merchant/products").param("pageSize", "20")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.total").value(3));

        // 状态过滤
        mockMvc.perform(get("/api/v1/merchant/products").param("status", "OFF_SHELF")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("SKU-M-2"));
    }

    @Test
    void merchantList_farmerAndGovernmentForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/merchant/products").header(HeaderNames.AUTHORIZATION, farmerBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
        mockMvc.perform(get("/api/v1/merchant/products").header(HeaderNames.AUTHORIZATION, bearer("GOVERNMENT", TENANT_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    @Test
    void productProfileUpdate_andLowStockAlert() throws Exception {
        ProductSku sku = seedReturning("SKU-ALERT-1", "预警商品", TENANT_A, "ON_SALE", 3);

        // 设置分类/单位/产地/详情与预警阈值（阈值 5 > 库存 3 → 预警）
        mockMvc.perform(put("/api/v1/products/" + sku.getId() + "/profile")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"GRAIN_OIL\",\"unit\":\"桶\",\"origin\":\"河南省鹿邑县\",\"detail\":\"石磨工艺\",\"stockAlert\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.category").value("GRAIN_OIL"))
                .andExpect(jsonPath("$.data.unit").value("桶"))
                .andExpect(jsonPath("$.data.stockAlert").value(5))
                .andExpect(jsonPath("$.data.lowStock").value(true));

        // 预警列表应包含该商品
        mockMvc.perform(get("/api/v1/merchant/products/low-stock")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data[0].skuCode").value("SKU-ALERT-1"));
    }

    @Test
    void lowStock_excludesHealthyStock() throws Exception {
        seedReturning("SKU-ALERT-OK", "库存充足", TENANT_A, "ON_SALE", 500);
        mockMvc.perform(get("/api/v1/merchant/products/low-stock")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void govSummary_platformAdminSeesScopeAndMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/gov/summary").header(HeaderNames.AUTHORIZATION, bearer("PLATFORM_ADMIN", "tenant_platform_000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.scope.all").value(true))
                .andExpect(jsonPath("$.data.metrics.version").isNotEmpty())
                .andExpect(jsonPath("$.data.snapshotAt").isNumber());
    }

    @Test
    void govSummary_governmentWithoutScopeSeesEmptyScope() throws Exception {
        mockMvc.perform(get("/api/v1/gov/summary").header(HeaderNames.AUTHORIZATION, bearer("GOVERNMENT", "tenant_yuzhuang_001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.scope.all").value(false))
                .andExpect(jsonPath("$.data.scope.tenantCount").value(0));
    }

    @Test
    void govSummary_villageForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/gov/summary").header(HeaderNames.AUTHORIZATION, villageBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    @Test
    void adminTenants_platformAdminAllowed_villageForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/tenants")
                        .header(HeaderNames.AUTHORIZATION, bearer("PLATFORM_ADMIN", "tenant_platform_000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data[0].tenantId").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/tenants").header(HeaderNames.AUTHORIZATION, villageBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    private void seed(String skuCode, String spuName, String tenantId, String status) {
        seedReturning(skuCode, spuName, tenantId, status, 10);
    }

    private ProductSku seedReturning(String skuCode, String spuName, String tenantId,
                                     String status, int stock) {
        ProductSku sku = ProductSku.builder()
                .tenantId(tenantId)
                .skuCode(skuCode)
                .spuName(spuName)
                .price(new BigDecimal("12.00"))
                .stock(stock)
                .stockAlert(0)
                .version(0)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }
}
