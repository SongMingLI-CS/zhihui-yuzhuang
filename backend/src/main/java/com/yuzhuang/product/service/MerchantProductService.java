package com.yuzhuang.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.service.impl.ProductAdminServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商家工作台商品查询（阶段 C）。
 *
 * <p>与公开读接口的差异（修复审计 P1-1「下架商品从列表消失」）：
 * <ul>
 *   <li>返回<b>本租户全部状态</b>商品（含草稿/在售/下架/归档），支持状态与关键词过滤与分页；</li>
 *   <li>数据域由已验证主体的 tenantId 推导；COOPERATIVE 仅本租户，VILLAGE/PLATFORM_ADMIN 额外
 *       可维护 global 共享目录；GOVERNMENT/FARMER 无该入口（由端点策略拒绝）。</li>
 * </ul>
 */
@Service
public class MerchantProductService {

    private static final int MAX_PAGE_SIZE = 100;

    /** 允许商品状态（与商户工作台一致；草稿/归档为阶段 C 新增，向后兼容 ON_SALE/OFF_SHELF）。 */
    private static final List<String> ALLOWED_STATUS =
            List.of("DRAFT", "ON_SALE", "OFF_SHELF", "ARCHIVED");

    private final ProductSkuMapper productSkuMapper;

    public MerchantProductService(ProductSkuMapper productSkuMapper) {
        this.productSkuMapper = productSkuMapper;
    }

    /**
     * 分页查询商家商品（含草稿/下架）。
     *
     * @param principal 已验证主体
     * @param status    状态过滤（可空）
     * @param keyword   名称/SKU 模糊（可空）
     * @param page      页码（从 1 开始）
     * @param pageSize  每页条数（1~100）
     */
    public PageResult<ProductSkuResponse> listProducts(AuthPrincipal principal, String status,
                                                       String keyword, int page, int pageSize) {
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        String tenant = principal.getTenantId();
        String normalizedStatus = normalizeStatus(status);
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        long total = productSkuMapper.selectCount(scopeWrapper(principal, tenant, normalizedStatus, kw));
        List<ProductSku> rows = productSkuMapper.selectList(
                scopeWrapper(principal, tenant, normalizedStatus, kw)
                        .orderByDesc(ProductSku::getId)
                        .last("LIMIT " + pageSize + " OFFSET " + ((long) (page - 1) * pageSize)));

        List<ProductSkuResponse> items = rows.stream().map(this::toResponse).toList();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return PageResult.<ProductSkuResponse>builder()
                .items(items)
                .page(page)
                .pageSize(pageSize)
                .total(total)
                .totalPages(totalPages)
                .build();
    }

    /**
     * 库存预警列表（阶段 C）：本租户内 {@code stock_alert > 0 且 stock <= stock_alert}
     * 且未归档的在售/下架商品，按库存升序，最多返回 {@value #MAX_PAGE_SIZE} 条。
     */
    public List<ProductSkuResponse> listLowStock(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        LambdaQueryWrapper<ProductSku> wrapper = scopeWrapper(principal, principal.getTenantId(), null, null);
        wrapper.ne(ProductSku::getStatus, "ARCHIVED")
                .gt(ProductSku::getStockAlert, 0)
                .apply("stock <= stock_alert")
                .orderByAsc(ProductSku::getStock)
                .last("LIMIT " + MAX_PAGE_SIZE);
        return productSkuMapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    /** 组装数据域 + 过滤条件（每次新建，避免 MyBatis-Plus wrapper 复用污染）。 */
    private LambdaQueryWrapper<ProductSku> scopeWrapper(AuthPrincipal principal, String tenant,
                                                        String status, String keyword) {
        LambdaQueryWrapper<ProductSku> wrapper = new LambdaQueryWrapper<>();
        boolean includeGlobal = UserRole.VILLAGE.name().equals(principal.getRole())
                || UserRole.PLATFORM_ADMIN.name().equals(principal.getRole());
        if (includeGlobal) {
            wrapper.and(q -> q.eq(ProductSku::getTenantId, tenant)
                    .or().eq(ProductSku::getTenantId, "global"));
        } else {
            wrapper.eq(ProductSku::getTenantId, tenant);
        }
        if (status != null) {
            wrapper.eq(ProductSku::getStatus, status);
        }
        if (keyword != null) {
            wrapper.and(q -> q.like(ProductSku::getSpuName, keyword)
                    .or().like(ProductSku::getSkuCode, keyword));
        }
        return wrapper;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toUpperCase();
        if (!ALLOWED_STATUS.contains(s)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "商品状态仅支持 " + String.join(" / ", ALLOWED_STATUS));
        }
        return s;
    }

    private ProductSkuResponse toResponse(ProductSku sku) {
        return ProductSkuResponse.builder()
                .id(sku.getId())
                .skuCode(sku.getSkuCode())
                .spuName(sku.getSpuName())
                .price(sku.getPrice())
                .stock(sku.getStock())
                .status(sku.getStatus())
                .tenantId(sku.getTenantId())
                .category(sku.getCategory())
                .unit(sku.getUnit())
                .origin(sku.getOrigin())
                .stockAlert(sku.getStockAlert())
                .lowStock(ProductAdminServiceImpl.isLowStock(sku))
                .updatedAt(sku.getUpdatedAt() == null ? null : sku.getUpdatedAt().toString())
                .build();
    }
}
