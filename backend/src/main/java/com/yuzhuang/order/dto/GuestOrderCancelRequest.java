package com.yuzhuang.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 匿名本人订单取消请求（阶段 E）：订单号 + 查询凭证 + 可选原因。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GuestOrderCancelRequest {

    /** 业务订单号 */
    @NotBlank(message = "订单号不能为空")
    @Size(max = 64, message = "订单号长度不能超过64")
    private String orderNo;

    /** 下单时返回的一次性查询凭证 */
    @NotBlank(message = "查询凭证不能为空")
    @Size(max = 128, message = "查询凭证长度不能超过128")
    private String queryToken;

    /** 取消原因（可选） */
    @Size(max = 200, message = "取消原因长度不能超过200")
    private String reason;
}
