package com.yuzhuang.product.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;
import com.yuzhuang.product.service.ProductAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品管理写接口（严格对齐 docs/api-spec.yaml /products 写操作）。
 *
 * <p>端点契约（自安全强化起需 COOPERATIVE/VILLAGE 的 JWT）：
 * <ul>
 *   <li>{@code POST /api/v1/products}：上架新商品（归属令牌 tenantId）；</li>
 *   <li>{@code PUT /api/v1/products/{id}}：编辑本租户商品（跨租户 404 + A1004）；</li>
 *   <li>{@code PATCH /api/v1/products/{id}/status}：上下架切换。</li>
 * </ul>
 * 商品归属/作用域租户统一取自 {@link AuthContext}（JWT），不再读 {@code X-Tenant-Id} 头。
 */
@Slf4j
@Tag(name = "商品管理", description = "商品上架/编辑/上下架写接口")
@RestController
@RequestMapping("/api/v1")
public class ProductAdminController {

    private final ProductAdminService productAdminService;

    public ProductAdminController(ProductAdminService productAdminService) {
        this.productAdminService = productAdminService;
    }

    @Operation(summary = "上架新商品")
    @PostMapping("/products")
    public ApiResponse<ProductSkuResponse> createProduct(
            @Valid @RequestBody ProductUpsertRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[product-admin] create tenantId={}, skuCode={}", tenantId, request.getSkuCode());
        return ApiResponse.success(productAdminService.createProduct(request, tenantId));
    }

    @Operation(summary = "编辑商品")
    @PutMapping("/products/{id}")
    public ApiResponse<ProductSkuResponse> updateProduct(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductUpsertRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[product-admin] update id={}, tenantId={}", id, tenantId);
        return ApiResponse.success(productAdminService.updateProduct(id, request, tenantId));
    }

    @Operation(summary = "商品上下架切换")
    @PatchMapping("/products/{id}/status")
    public ApiResponse<ProductSkuResponse> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductStatusRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[product-admin] update status id={}, status={}, tenantId={}", id, request.getStatus(), tenantId);
        return ApiResponse.success(productAdminService.updateStatus(id, request, tenantId));
    }
}
