package com.yuzhuang.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 支付（沙箱演示）请求（严格对齐 docs/api-spec.yaml PaymentSandboxPayRequest）。
 *
 * <p>真实支付通道接入后由对应 Adapter 将渠道回调转换为统一请求，本请求为演示沙箱语义。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSandboxPayRequest {

    /** 支付渠道标识（沙箱固定为 SANDBOX，预留真实渠道枚举） */
    @NotBlank(message = "支付渠道不能为空")
    @Size(max = 32)
    private String channel;

    /** 渠道支付流水号（支付幂等去重键） */
    @NotBlank(message = "支付流水号不能为空")
    @Size(max = 64)
    private String tradeNo;
}
