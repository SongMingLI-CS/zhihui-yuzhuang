package com.yuzhuang.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;
import com.yuzhuang.product.service.ProductAdminService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 商品管理写接口实现。
 *
 * <p>状态契约与读接口一致：在售 = {@code ON_SALE}、下架 = {@code OFF_SHELF}。
 * 描述与占位图地址沿用读接口的派生/预置逻辑，保证读写返回结构一致。
 */
@Service
public class ProductAdminServiceImpl implements ProductAdminService {

    private static final String DEFAULT_TENANT_ID = "global";
    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";
    private static final String IMAGE_URL_PREFIX = "https://cdn.yuzhuang.example/specialty/";
    private static final String IMAGE_URL_SUFFIX = ".jpg";
    private static final String DESCRIPTION_PREFIX = "于庄原产地直发 · ";
    private static final String DESCRIPTION_SUFFIX = "，传统工艺，地道风味";

    private final ProductSkuMapper productSkuMapper;

    public ProductAdminServiceImpl(ProductSkuMapper productSkuMapper) {
        this.productSkuMapper = productSkuMapper;
    }

    @Override
    public ProductSkuResponse createProduct(ProductUpsertRequest request, String tenantId) {
        String tenant = normalizeTenantId(tenantId);
        String status = normalizeStatus(request.getStatus());

        Long exists = productSkuMapper.selectCount(new LambdaQueryWrapper<ProductSku>()
                .eq(ProductSku::getTenantId, tenant)
                .eq(ProductSku::getSkuCode, request.getSkuCode().trim()));
        if (exists > 0) {
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }

        ProductSku sku = ProductSku.builder()
                .tenantId(tenant)
                .skuCode(request.getSkuCode().trim())
                .spuName(request.getSpuName().trim())
                .price(request.getPrice())
                .stock(request.getStock())
                .version(0)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            productSkuMapper.insert(sku);
        } catch (DuplicateKeyException ex) {
            // 并发下唯一约束兜底
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }
        return toResponse(sku);
    }

    @Override
    public ProductSkuResponse updateProduct(Long id, ProductUpsertRequest request) {
        ProductSku sku = getOrThrow(id);
        sku.setSkuCode(request.getSkuCode().trim());
        sku.setSpuName(request.getSpuName().trim());
        sku.setPrice(request.getPrice());
        sku.setStock(request.getStock());
        sku.setStatus(normalizeStatus(request.getStatus()));
        try {
            productSkuMapper.updateById(sku);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.SKU_CODE_CONFLICT);
        }
        return toResponse(sku);
    }

    @Override
    public ProductSkuResponse updateStatus(Long id, ProductStatusRequest request) {
        ProductSku sku = getOrThrow(id);
        sku.setStatus(normalizeStatus(request.getStatus()));
        productSkuMapper.updateById(sku);
        return toResponse(sku);
    }

    private ProductSku getOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品 ID 不能为空");
        }
        ProductSku sku = productSkuMapper.selectById(id);
        if (sku == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        return sku;
    }

    private String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 状态规整：空默认在售；仅接受 ON_SALE / OFF_SHELF。 */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_ON_SALE;
        }
        String s = status.trim();
        if (STATUS_ON_SALE.equals(s) || STATUS_OFF_SHELF.equals(s)) {
            return s;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "商品状态仅支持 ON_SALE / OFF_SHELF");
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
                .description(DESCRIPTION_PREFIX + sku.getSpuName() + DESCRIPTION_SUFFIX)
                .imageUrl(IMAGE_URL_PREFIX + sku.getSkuCode() + IMAGE_URL_SUFFIX)
                .build();
    }
}
