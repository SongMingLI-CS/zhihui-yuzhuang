package com.yuzhuang.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 商品上架/编辑请求（对齐 docs/api-spec.yaml ProductUpsertRequest）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductUpsertRequest {

    /** SKU 编码（同租户内唯一） */
    @NotBlank(message = "SKU 编码不能为空")
    @Size(max = 64, message = "SKU 编码长度不能超过64")
    private String skuCode;

    /** SPU/商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 255, message = "商品名称长度不能超过255")
    private String spuName;

    /** 售价（元，大于0） */
    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价必须大于0")
    private BigDecimal price;

    /** 可售库存（>=0） */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 商品状态（ON_SALE 在售 / OFF_SHELF 下架，缺省 ON_SALE） */
    private String status;
}
