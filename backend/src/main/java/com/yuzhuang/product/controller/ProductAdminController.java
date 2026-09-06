package com.yuzhuang.product.controller;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品管理写接口（严格对齐 docs/api-spec.yaml /products 写操作）。
 *
 * <p>端点契约：
 * <ul>
 *   <li>{@code POST /api/v1/products}：上架新商品（Header X-Tenant-Id 缺省 global）；</li>
 *   <li>{@code PUT /api/v1/products/{id}}：编辑商品；</li>
 *   <li>{@code PATCH /api/v1/products/{id}/status}：上下架切换。</li>
 * </ul>
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
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @Valid @RequestBody ProductUpsertRequest request) {
        log.debug("[product-admin] create tenantId={}, skuCode={}", tenantId, request.getSkuCode());
        return ApiResponse.success(productAdminService.createProduct(request, tenantId));
    }

    @Operation(summary = "编辑商品")
    @PutMapping("/products/{id}")
    public ApiResponse<ProductSkuResponse> updateProduct(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductUpsertRequest request) {
        log.debug("[product-admin] update id={}", id);
        return ApiResponse.success(productAdminService.updateProduct(id, request));
    }

    @Operation(summary = "商品上下架切换")
    @PatchMapping("/products/{id}/status")
    public ApiResponse<ProductSkuResponse> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProductStatusRequest request) {
        log.debug("[product-admin] update status id={}, status={}", id, request.getStatus());
        return ApiResponse.success(productAdminService.updateStatus(id, request));
    }
}
