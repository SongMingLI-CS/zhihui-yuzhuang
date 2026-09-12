package com.yuzhuang.product.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.service.MerchantProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家工作台商品接口（阶段 C）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/merchant/products}：分页查询本租户商品，<b>包含草稿/在售/下架/归档</b>；</li>
 *   <li>{@code GET /api/v1/merchant/products/low-stock}：库存预警列表（阈值 &gt;0 且库存 ≤ 阈值）。</li>
 * </ul>
 * 数据域由 JWT 主体推导（不读 {@code X-Tenant-Id}）。
 */
@Slf4j
@Tag(name = "商家工作台", description = "商家商品分页查询与库存预警")
@RestController
@RequestMapping("/api/v1/merchant")
public class MerchantProductController {

    private final MerchantProductService merchantProductService;

    public MerchantProductController(MerchantProductService merchantProductService) {
        this.merchantProductService = merchantProductService;
    }

    @Operation(summary = "分页查询本租户商品（含草稿/下架/归档）")
    @GetMapping("/products")
    public ApiResponse<PageResult<ProductSkuResponse>> listProducts(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        log.debug("[merchant] list products status={}, keyword={}", status, keyword);
        return ApiResponse.success(
                merchantProductService.listProducts(AuthContext.require(), status, keyword, page, pageSize));
    }

    @Operation(summary = "库存预警列表（库存 ≤ 预警阈值，且未归档）")
    @GetMapping("/products/low-stock")
    public ApiResponse<List<ProductSkuResponse>> listLowStock() {
        log.debug("[merchant] list low-stock");
        return ApiResponse.success(merchantProductService.listLowStock(AuthContext.require()));
    }
}

