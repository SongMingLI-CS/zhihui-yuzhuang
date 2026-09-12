package com.yuzhuang.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 库存实体（对应 t_product_sku）。
 *
 * <p>{@code stock} 为实时可售库存，扣减走应用层 CAS 条件更新
 * （{@code UPDATE ... SET stock = stock - ? WHERE id=? AND stock >= ?}）；
 * {@code version} 随每次成功扣减自增，供审计与并发增强。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_product_sku")
public class ProductSku {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 多租户标识 */
    private String tenantId;

    /** SKU 编码 */
    private String skuCode;

    /** SPU/商品名称 */
    private String spuName;

    /** 售价（元，两位小数） */
    private BigDecimal price;

    /** 实时可售库存 */
    private Integer stock;

    /** 乐观锁版本号（随 CAS 扣减自增） */
    private Integer version;

    /** 状态：ACTIVE 在售 / INACTIVE 下架 */
    private String status;

    /** 商品分类（如 GRAIN_OIL 粮油 / FRUIT 果蔬 / OTHER 其他） */
    private String category;

    /** 计量单位（件/袋/箱/斤等） */
    private String unit;

    /** 产地 */
    private String origin;

    /** 商品详情（纯文本；富文本/媒体按约定延后） */
    private String detail;

    /** 库存预警阈值（stock <= 阈值 时进入预警列表） */
    private Integer stockAlert;

    /** 创建人用户名 */
    private String createdBy;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
