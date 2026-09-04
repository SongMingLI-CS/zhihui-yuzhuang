package com.yuzhuang.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuzhuang.inventory.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商品 SKU Mapper。
 *
 * <p>{@link #decreaseStockCas} 为 CAS 乐观锁库存扣减：
 * 仅当 {@code stock >= quantity} 才原子扣减并自增版本号，
 * 返回影响行数；返回 0 表示库存不足/超卖被拒绝（由 Service 抛
 * {@code INVENTORY_STOCK_OUT}）。
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * CAS 乐观锁扣减库存：原子执行
     * {@code stock = stock - #{quantity}} 且 {@code version = version + 1}，
     * WHERE 条件携带 {@code stock >= #{quantity}} 防超卖。
     *
     * @param skuId    SKU 主键
     * @param quantity 扣减数量（>0）
     * @return 影响行数：1 成功扣减；0 库存不足（拒绝）
     */
    @Update("UPDATE t_product_sku SET stock = stock - #{quantity}, version = version + 1 " +
            "WHERE id = #{skuId} AND stock >= #{quantity}")
    int decreaseStockCas(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 按租户查询在售（ON_SALE）商品列表（特产读接口专用）。
     *
     * <p>过滤契约：
     * {@code status = 'ON_SALE'} 且租户归属
     * {@code tenant_id = #{tenantId} OR tenant_id = 'global'}（全局商品对任意租户可见），
     * 返回结果按主键升序保证稳定排序。
     *
     * @param tenantId 租户标识（空值由 Service 层规整为 global）
     * @return 在售 SKU 列表（可能为空，不会返回下架 OFF_SHELF 商品）
     */
    @Select("SELECT id, tenant_id, sku_code, spu_name, price, stock, version, status, created_at " +
            "FROM t_product_sku " +
            "WHERE status = 'ON_SALE' AND (tenant_id = #{tenantId} OR tenant_id = 'global') " +
            "ORDER BY id ASC")
    List<ProductSku> selectAvailableList(@Param("tenantId") String tenantId);
}
