package com.yuzhuang.order.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 订单发货请求（阶段 E/C，可选）：填写承运商与物流单号。
 *
 * <p>作为 {@code POST /api/v1/orders/{orderNo}/ship} 的可选请求体，
 * 不传时保持原有「一键出库」语义（物流信息留空，可后续补录）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderShipRequest {

    /** 承运商（如 顺丰/中通/邮政），可空 */
    @Size(max = 64, message = "承运商长度不能超过64")
    private String carrier;

    /** 物流单号，可空 */
    @Size(max = 64, message = "物流单号长度不能超过64")
    private String trackingNo;
}
