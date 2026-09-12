package com.yuzhuang.order.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 订单取消请求（阶段 E）：取消原因可选。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelRequest {

    /** 取消原因（用户主动取消/其他），可空 */
    @Size(max = 200, message = "取消原因长度不能超过200")
    private String reason;
}
