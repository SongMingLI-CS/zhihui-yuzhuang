package com.yuzhuang.product;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 特产商品读接口（列表 + 详情）。
 *
 * <p>端点契约：
 * <ul>
 *   <li>{@code GET /api/v1/products}：从 Header {@code X-Tenant-Id} 取租户（缺省为
 *       {@code global}），返回在售商品列表（本租户 ∪ 全局，状态 ON_SALE）；</li>
 *   <li>{@code GET /api/v1/products/{id}}：按 SKU 主键返回详情，
 *       商品不存在映射 HTTP 404 + A1004（由 {@code GlobalExceptionHandler} 统一处理）。</li>
 * </ul>
 * 所有响应统一包裹 {@link ApiResponse}。
 */
@Slf4j
@Tag(name = "特产商品", description = "特产商品列表与详情读接口")
@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @Operation(summary = "特产商品列表（租户在售商品 + 全局共享在售商品）")
    @GetMapping("/products")
    public ApiResponse<List<ProductSkuResponse>> listProducts(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId) {
        log.debug("[product] list products, tenantId={}", tenantId);
        return ApiResponse.success(productQueryService.listAvailableProducts(tenantId));
    }

    @Operation(summary = "特产商品详情（不存在返回 A1004 / HTTP 404）")
    @GetMapping("/products/{id}")
    public ApiResponse<ProductSkuResponse> getProduct(@PathVariable("id") Long id) {
        log.debug("[product] get detail, skuId={}", id);
        return ApiResponse.success(productQueryService.getProductDetail(id));
    }
}
