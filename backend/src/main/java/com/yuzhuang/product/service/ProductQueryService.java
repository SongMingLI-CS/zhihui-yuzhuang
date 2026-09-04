package com.yuzhuang.product.service;

import com.yuzhuang.product.dto.ProductSkuResponse;

import java.util.List;

/**
 * 特产商品读接口服务：在售商品列表与商品详情查询。
 *
 * <p>列表语义：仅返回 {@code status = ON_SALE} 的商品，租户归属为
 * {@code tenant_id = #{tenantId} OR tenant_id = 'global'}；空租户按 {@code global} 兜底。
 * 详情语义：按 SKU 主键查询，不存在（含非法/越界 ID）抛出
 * {@code ResultCode.NOT_FOUND(A1004)}。
 */
public interface ProductQueryService {

    /**
     * 查询指定租户可见的在售商品列表（含全局共享商品）。
     *
     * @param tenantId 租户标识（null/空白按 {@code global} 兜底）
     * @return 在售商品响应列表（可能为空；不含下架商品）
     */
    List<ProductSkuResponse> listAvailableProducts(String tenantId);

    /**
     * 查询商品详情。
     *
     * @param skuId SKU 主键
     * @return 商品详情响应
     * @throws com.yuzhuang.common.exception.BusinessException 商品不存在时抛出
     *         {@code ResultCode.NOT_FOUND(A1004)}
     */
    ProductSkuResponse getProductDetail(Long skuId);
}
