package com.yuzhuang.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 商品扩展资料更新请求（阶段 C，非媒体部分）。
 *
 * <p>独立于 {@link ProductUpsertRequest}：后者承载「编码/名称/价格/库存/状态」核心字段且
 * 已有稳定契约与测试；本 DTO 仅承载分类/单位/产地/详情/库存预警阈值，
 * 通过 {@code PUT /api/v1/products/{id}/profile} 更新，避免破坏既有调用方。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductProfileRequest {

    /** 商品分类（可空；长度 <= 64） */
    @Size(max = 64, message = "商品分类长度不能超过64")
    private String category;

    /** 计量单位（可空；长度 <= 16） */
    @Size(max = 16, message = "计量单位长度不能超过16")
    private String unit;

    /** 产地（可空；长度 <= 128） */
    @Size(max = 128, message = "产地长度不能超过128")
    private String origin;

    /** 商品详情（纯文本，可空；长度 <= 4000） */
    @Size(max = 4000, message = "商品详情长度不能超过4000")
    private String detail;

    /** 库存预警阈值（>=0，缺省 0 表示不预警） */
    @Min(value = 0, message = "库存预警阈值不能为负数")
    private Integer stockAlert;
}
