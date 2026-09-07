package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 支付结果响应（对齐 docs/api-spec.yaml PaymentSandboxResponse）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSandboxResponse {

    /** 业务订单号 */
    private String orderNo;

    /** 支付后订单状态（成功为 PROCESSING） */
    private String status;

    /** 支付渠道 */
    private String channel;

    /** 渠道支付流水号 */
    private String tradeNo;

    /** 支付成功时间（epoch 毫秒） */
    private long paidAt;
}
