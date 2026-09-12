package com.yuzhuang.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 匿名本人订单查询请求（阶段 E）。
 *
 * <p>必须同时提供「订单号 + 下单时一次性查询凭证」；服务端对凭证做 PBKDF2 常量时间校验，
 * 仅凭订单号无法读取他人订单与收货信息。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GuestOrderLookupRequest {

    /** 业务订单号 */
    @NotBlank(message = "订单号不能为空")
    @Size(max = 64, message = "订单号长度不能超过64")
    private String orderNo;

    /** 下单时返回的一次性查询凭证 */
    @NotBlank(message = "查询凭证不能为空")
    @Size(max = 128, message = "查询凭证长度不能超过128")
    private String queryToken;
}
