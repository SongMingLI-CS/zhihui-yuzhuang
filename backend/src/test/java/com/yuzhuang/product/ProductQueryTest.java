package com.yuzhuang.product;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.service.ProductQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 特产商品读接口 · 列表/详情查询测试（test profile，H2 PostgreSQL 兼容模式）。
 *
 * <p>复用 {@code schema-test.sql} 表结构，用例隔离通过 {@code @BeforeEach} 清空
 * {@code t_product_sku} 实现。契约按任务规格：在售 = {@code status = 'ON_SALE'}、
 * 下架 = {@code status = 'OFF_SHELF'}，租户可见域 = 本租户 ∪ 全局（global）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>列表查询：在售商品被正确查出、下架商品被过滤、租户数据隔离（不含他租户商品）；</li>
 *   <li>列表查询：租户缺省回退 global；</li>
 *   <li>详情查询：既有商品返回详情、不存在抛 {@code A1004}；</li>
 *   <li>HTTP 端点契约：{@code GET /api/v1/products}（含 Header / 缺省）、
 *       {@code GET /api/v1/products/{id}}（200 / 404 A1004）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductQueryTest {

    private static final String GLOBAL = "global";
    private static final String TENANT_A = "tenant_yuzhuang_001";
    private static final String TENANT_B = "tenant_yuzhuang_002";

    /** 在售 / 下架状态（读接口契约） */
    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";

    @Autowired
    private ProductQueryService productQueryService;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private MockMvc mockMvc;

    /** 每个用例前清空 SKU 表并重置租户上下文，保证断言基数确定。 */
    @BeforeEach
    void cleanSkuTable() {
        TenantContext.clear();
        productSkuMapper.delete(null);
    }

    // ============================================================
    // (a) 列表查询：在售查出 / 下架过滤 / 租户隔离
    // ============================================================

    @Test
    void list_returnsOnlyOnSale_withTenantScopeIsolation() {
        ProductSku globalSesame = seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");
        ProductSku globalHoney = seedSku(GLOBAL, "SKU-HONEY-001", "于庄荆条土蜂蜜", STATUS_ON_SALE, "128.00");
        // 下架商品（全局）：必须被过滤
        seedSku(GLOBAL, "SKU-FLOUR-001", "于庄富硒小麦粉", STATUS_OFF_SHELF, "39.90");
        // 本租户在售商品：应随全局商品一起返回
        ProductSku tenantASesame = seedSku(TENANT_A, "SKU-TA-SESAME-001", "于庄特供香油", STATUS_ON_SALE, "88.00");
        // 他租户在售商品：查询 A 租户时不得返回
        seedSku(TENANT_B, "SKU-TB-HONEY-001", "他租户蜂蜜", STATUS_ON_SALE, "99.00");

        // 全局视角：仅 2 条全局在售，下架与他租户均不出现
        List<ProductSkuResponse> globalList = productQueryService.listAvailableProducts(GLOBAL);
        assertThat(skuCodes(globalList)).isEqualTo(Set.of("SKU-SESAME-001", "SKU-HONEY-001"));

        // A 租户视角：全局在售 2 条 + 本租户在售 1 条；不含下架 SKU-FLOUR-001、不含他租户 SKU
        List<ProductSkuResponse> tenantAList = productQueryService.listAvailableProducts(TENANT_A);
        assertThat(skuCodes(tenantAList)).isEqualTo(
                Set.of("SKU-SESAME-001", "SKU-HONEY-001", "SKU-TA-SESAME-001"));
        assertThat(tenantAList).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(STATUS_ON_SALE));

        // 全量字段非空校验（在售商品能携带完整展示信息）
        assertThat(globalList).allSatisfy(r -> {
            assertThat(r.getId()).isNotNull();
            assertThat(r.getSkuCode()).isNotBlank();
            assertThat(r.getSpuName()).isNotBlank();
            assertThat(r.getPrice()).isPositive();
            assertThat(r.getStock()).isNotNull();
            assertThat(r.getTenantId()).isEqualTo(GLOBAL);
            assertThat(r.getDescription()).isNotBlank();
            assertThat(r.getImageUrl()).isNotBlank();
        });
        assertThat(globalList.stream().map(ProductSkuResponse::getSkuCode)).containsExactlyInAnyOrder(
                globalSesame.getSkuCode(), globalHoney.getSkuCode());
        assertThat(tenantAList.stream().map(ProductSkuResponse::getId)).contains(tenantASesame.getId());
    }

    // ============================================================
    // (b) 列表查询：租户缺省回退 global
    // ============================================================

    @Test
    void list_defaultsToGlobal_whenTenantBlank() {
        seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");
        seedSku(GLOBAL, "SKU-FLOUR-001", "于庄富硒小麦粉", STATUS_OFF_SHELF, "39.90");

        assertThat(skuCodes(productQueryService.listAvailableProducts(null))).isEqualTo(Set.of("SKU-SESAME-001"));
        assertThat(skuCodes(productQueryService.listAvailableProducts("  "))).isEqualTo(Set.of("SKU-SESAME-001"));
    }

    // ============================================================
    // (c) 详情查询：既有商品返回详情 / 不存在抛 A1004
    // ============================================================

    @Test
    void detail_returnsExistingProduct() {
        ProductSku sku = seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");

        ProductSkuResponse detail = productQueryService.getProductDetail(sku.getId());

        assertThat(detail).isNotNull();
        assertThat(detail.getId()).isEqualTo(sku.getId());
        assertThat(detail.getSkuCode()).isEqualTo("SKU-SESAME-001");
        assertThat(detail.getSpuName()).isEqualTo("于庄小磨香油");
        assertThat(detail.getPrice()).isEqualByComparingTo(new BigDecimal("68.00"));
        assertThat(detail.getStatus()).isEqualTo(STATUS_ON_SALE);
        assertThat(detail.getTenantId()).isEqualTo(GLOBAL);
        assertThat(detail.getDescription()).isNotBlank();
        assertThat(detail.getImageUrl()).isNotBlank();
    }

    @Test
    void detail_notFound_throwsA1004() {
        ProductSku sku = seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");
        long missingId = sku.getId() + 99_999L;

        assertThatThrownBy(() -> productQueryService.getProductDetail(missingId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode());
                });
    }

    // ============================================================
    // (d) HTTP 端点契约
    // ============================================================

    @Test
    void listEndpoint_withTenantHeader_returnsOnSaleProducts() throws Exception {
        seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");
        seedSku(GLOBAL, "SKU-FLOUR-001", "于庄富硒小麦粉", STATUS_OFF_SHELF, "39.90");
        seedSku(TENANT_A, "SKU-TA-SESAME-001", "于庄特供香油", STATUS_ON_SALE, "88.00");

        // A 租户：全局在售 1 条 + 本租户在售 1 条 = 2 条；下架 SKU-FLOUR-001 被过滤
        mockMvc.perform(get("/api/v1/products").header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value(STATUS_ON_SALE))
                .andExpect(jsonPath("$.data[?(@.skuCode == 'SKU-FLOUR-001')]").isEmpty());
    }

    @Test
    void listEndpoint_withoutTenantHeader_defaultsGlobal() throws Exception {
        seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");
        seedSku(GLOBAL, "SKU-FLOUR-001", "于庄富硒小麦粉", STATUS_OFF_SHELF, "39.90");

        // 无租户 Header：回退 global，仅 1 条在售
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuCode").value("SKU-SESAME-001"));
    }

    @Test
    void detailEndpoint_returnsProductDetail() throws Exception {
        ProductSku sku = seedSku(GLOBAL, "SKU-SESAME-001", "于庄小磨香油", STATUS_ON_SALE, "68.00");

        mockMvc.perform(get("/api/v1/products/{id}", sku.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.id").value(sku.getId()))
                .andExpect(jsonPath("$.data.skuCode").value("SKU-SESAME-001"))
                .andExpect(jsonPath("$.data.status").value(STATUS_ON_SALE))
                .andExpect(jsonPath("$.data.price").value(68.00))
                .andExpect(jsonPath("$.data.imageUrl").isNotEmpty());
    }

    @Test
    void detailEndpoint_notFound_returns404A1004() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private ProductSku seedSku(String tenantId, String skuCode, String spuName,
                               String status, String price) {
        ProductSku sku = ProductSku.builder()
                .tenantId(tenantId)
                .skuCode(skuCode)
                .spuName(spuName)
                .price(new BigDecimal(price))
                .stock(100)
                .version(0)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }

    private Set<String> skuCodes(List<ProductSkuResponse> list) {
        return list.stream().map(ProductSkuResponse::getSkuCode).collect(Collectors.toSet());
    }
}
