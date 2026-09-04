package com.yuzhuang.order.dto;

import com.yuzhuang.order.enums.OrderSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 特产下单请求（严格对齐 docs/api-spec.yaml OrderCheckoutRequest）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCheckoutRequest {

    /** 订单来源渠道（H5_PRIVATE / DOUYIN / KUAISHOU / B2B_PORTAL） */
    @NotNull(message = "订单来源渠道不能为空")
    private OrderSource orderSource;

    /** 备注（选填） */
    @Size(max = 200, message = "备注长度不能超过200")
    private String remark;

    /** 下单商品明细（至少 1 项） */
    @NotEmpty(message = "下单商品不能为空")
    @Valid
    private List<@Valid OrderItemRequest> items;

    /** 收货人信息 */
    @NotNull(message = "收货信息不能为空")
    @Valid
    private ReceiverAddress receiverAddress;
}
