package com.yuzhuang.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 特产商品列表/详情响应 DTO。
 *
 * <p>对齐特产读接口契约（{@code GET /api/v1/products}）字段：
 * {@code id / skuCode / spuName / price / stock / status / tenantId / description / imageUrl}。
 * 其中 {@code description} 由 SPU 名称派生预置文案，{@code imageUrl} 为预置高清特产占位图地址，
 * 二者由 {@code ProductQueryServiceImpl} 装配，不含于 {@code t_product_sku} 表结构。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSkuResponse {

    /** SKU 主键 */
    private Long id;

    /** SKU 编码 */
    private String skuCode;

    /** SPU/商品名称 */
    private String spuName;

    /** 售价（元，两位小数） */
    private BigDecimal price;

    /** 实时可售库存 */
    private Integer stock;

    /** 商品状态（在售为 ON_SALE / 下架为 OFF_SHELF） */
    private String status;

    /** 多租户标识（global 表示全局共享商品） */
    private String tenantId;

    /** 商品描述（由 spuName 派生预置文案） */
    private String description;

    /** 高清特产占位图地址（预置） */
    private String imageUrl;

    // ---------- 阶段 C（非媒体）扩展字段 ----------

    /** 商品分类 */
    private String category;

    /** 计量单位 */
    private String unit;

    /** 产地 */
    private String origin;

    /** 商品详情（纯文本） */
    private String detail;

    /** 库存预警阈值 */
    private Integer stockAlert;

    /** 是否处于库存预警（stock <= stockAlert，且非归档） */
    private Boolean lowStock;

    /** 创建人 */
    private String createdBy;

    /** 最近更新时间（ISO 字符串） */
    private String updatedAt;
}
