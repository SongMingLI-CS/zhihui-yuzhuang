package com.yuzhuang.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 下单商品项（对齐 docs/api-spec.yaml OrderCheckoutRequest.items[]）。
 *
 * <p>{@code expectedUnitPrice} 为前端展示单价，仅作参考；服务端以
 * 数据库 SKU 实时价作为订单明细/总价的唯一计价依据。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequest {

    /** SKU ID */
    @NotNull(message = "商品SKU不能为空")
    private Long skuId;

    /** 购买数量 */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为1")
    private Integer quantity;

    /** 前端校验单价（元） */
    @NotNull(message = "商品单价不能为空")
    @DecimalMin(value = "0.01", message = "商品单价必须大于0")
    private BigDecimal expectedUnitPrice;
}
