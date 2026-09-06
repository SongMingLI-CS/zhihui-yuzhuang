package com.yuzhuang.product.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 商品状态更新请求（对齐 docs/api-spec.yaml ProductStatusRequest）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductStatusRequest {

    /** 目标状态（ON_SALE 在售 / OFF_SHELF 下架） */
    @NotBlank(message = "商品状态不能为空")
    private String status;
}
