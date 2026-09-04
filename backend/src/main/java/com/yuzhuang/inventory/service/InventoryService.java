package com.yuzhuang.inventory.service;

import com.yuzhuang.inventory.entity.ProductSku;

/**
 * 库存领域服务：对 SKU 实时库存的查询与 CAS 扣减入口。
 */
public interface InventoryService {

    /**
     * 按主键查询 SKU（不存在返回 {@code null}）。
     *
     * @param skuId SKU 主键
     * @return SKU 或 null
     */
    ProductSku getSku(Long skuId);

    /**
     * CAS 乐观锁扣减库存。
     *
     * <p>影响行数为 0（库存不足）时抛出
     * {@code ResultCode.INVENTORY_STOCK_OUT} 业务异常；
     * 该方法必须运行在调用方（下单）事务内，以便订单后续失败时
     * 随事务一并回滚扣减。
     *
     * @param skuId    SKU 主键
     * @param quantity 扣减数量（>0）
     */
    void deductStock(Long skuId, Integer quantity);
}
