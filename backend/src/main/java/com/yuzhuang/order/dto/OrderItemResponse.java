package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 订单明细行响应（对齐 docs/api-spec.yaml OrderItemResponse）：
 * {@code skuId / quantity / unitPrice / subtotal}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemResponse {

    /** SKU 主键 */
    private Long skuId;

    /** 购买数量 */
    private Integer quantity;

    /** 落单时点单价（元） */
    private BigDecimal unitPrice;

    /** 小计（单价 × 数量） */
    private BigDecimal subtotal;
}
