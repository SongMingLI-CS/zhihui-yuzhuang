package com.yuzhuang.inventory.service.impl;

import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.inventory.service.InventoryService;
import org.springframework.stereotype.Service;

/**
 * 库存领域服务实现。
 *
 * <p>注意：{@link #deductStock} 刻意<b>不</b>开启独立事务，
 * 从而加入调用方（下单 @Transactional）事务；一旦订单落库失败，
 * 扣减随整体回滚，杜绝"扣了库存却没单"的资损。
 */
@Service
public class InventoryServiceImpl implements InventoryService {

    private final ProductSkuMapper productSkuMapper;

    public InventoryServiceImpl(ProductSkuMapper productSkuMapper) {
        this.productSkuMapper = productSkuMapper;
    }

    @Override
    public ProductSku getSku(Long skuId) {
        return productSkuMapper.selectById(skuId);
    }

    @Override
    public void deductStock(Long skuId, Integer quantity) {
        int rows = productSkuMapper.decreaseStockCas(skuId, quantity);
        // CAS 影响行数为 0：要么库存不足，要么 SKU 已被置为下架/删除
        if (rows == 0) {
            throw new BusinessException(ResultCode.INVENTORY_STOCK_OUT);
        }
    }
}
