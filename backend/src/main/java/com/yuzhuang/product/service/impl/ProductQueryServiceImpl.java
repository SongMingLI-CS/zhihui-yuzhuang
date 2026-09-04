package com.yuzhuang.product.service.impl;

import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.service.ProductQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 特产商品读接口服务实现。
 *
 * <p>查询语义与持久层 {@link ProductSkuMapper#selectAvailableList} 对齐：
 * 在售 = {@code status = 'ON_SALE'}，租户可见域 = 本租户 ∪ 全局（global）。
 * {@code description} 由 {@code spuName} 派生预置文案，{@code imageUrl}
 * 使用预置高清特产占位图地址（按 SKU 编码稳定生成，便于缓存与测试断言）。
 */
@Service
public class ProductQueryServiceImpl implements ProductQueryService {

    /** 缺省租户标识（与 TenantContext / 契约缺省一致） */
    private static final String DEFAULT_TENANT_ID = "global";

    /** 高清特产占位图地址前缀（预置，按 SKU 编码拼接图片文件名） */
    private static final String IMAGE_URL_PREFIX = "https://cdn.yuzhuang.example/specialty/";

    /** 图片文件扩展名 */
    private static final String IMAGE_URL_SUFFIX = ".jpg";

    /** description 预置文案前缀（由 spuName 派生） */
    private static final String DESCRIPTION_PREFIX = "于庄原产地直发 · ";

    /** description 预置文案后缀 */
    private static final String DESCRIPTION_SUFFIX = "，传统工艺，地道风味";

    private final ProductSkuMapper productSkuMapper;

    public ProductQueryServiceImpl(ProductSkuMapper productSkuMapper) {
        this.productSkuMapper = productSkuMapper;
    }

    @Override
    public List<ProductSkuResponse> listAvailableProducts(String tenantId) {
        String effectiveTenant = normalizeTenantId(tenantId);
        return productSkuMapper.selectAvailableList(effectiveTenant).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public ProductSkuResponse getProductDetail(Long skuId) {
        if (skuId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品 ID 不能为空");
        }
        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return toResponse(sku);
    }

    /** 租户规整：null/空白一律回退 {@code global}，并去除首尾空白。 */
    private String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 实体 → 响应装配：description / imageUrl 为派生/预置字段。 */
    private ProductSkuResponse toResponse(ProductSku sku) {
        String spuName = sku.getSpuName();
        String description = DESCRIPTION_PREFIX + spuName + DESCRIPTION_SUFFIX;
        String imageUrl = IMAGE_URL_PREFIX + sku.getSkuCode() + IMAGE_URL_SUFFIX;
        return ProductSkuResponse.builder()
                .id(sku.getId())
                .skuCode(sku.getSkuCode())
                .spuName(spuName)
                .price(sku.getPrice())
                .stock(sku.getStock())
                .status(sku.getStatus())
                .tenantId(sku.getTenantId())
                .description(description)
                .imageUrl(imageUrl)
                .build();
    }
}
