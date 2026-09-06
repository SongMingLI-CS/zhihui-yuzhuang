package com.yuzhuang.product.service;

import com.yuzhuang.product.dto.ProductSkuResponse;
import com.yuzhuang.product.dto.ProductStatusRequest;
import com.yuzhuang.product.dto.ProductUpsertRequest;

/**
 * 商品管理写接口服务（上架/编辑/上下架）。
 */
public interface ProductAdminService {

    /**
     * 上架新商品（SKU 编码在同租户内唯一）。
     *
     * @param request  商品信息
     * @param tenantId 租户标识（null/空白按 {@code global} 兜底）
     * @return 创建后的商品响应
     * @throws com.yuzhuang.common.exception.BusinessException SKU 编码重复抛 B2004
     */
    ProductSkuResponse createProduct(ProductUpsertRequest request, String tenantId);

    /**
     * 编辑商品（名称/价格/库存/状态/SKU 编码）。
     *
     * @param id      SKU 主键
     * @param request 商品信息
     * @return 更新后的商品响应
     * @throws com.yuzhuang.common.exception.BusinessException 商品不存在抛 A1004
     */
    ProductSkuResponse updateProduct(Long id, ProductUpsertRequest request);

    /**
     * 商品上下架（状态切换）。
     *
     * @param id      SKU 主键
     * @param request 目标状态
     * @return 更新后的商品响应
     * @throws com.yuzhuang.common.exception.BusinessException 商品不存在抛 A1004
     */
    ProductSkuResponse updateStatus(Long id, ProductStatusRequest request);
}
