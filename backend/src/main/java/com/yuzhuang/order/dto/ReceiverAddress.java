package com.yuzhuang.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 收货人信息（对齐 docs/api-spec.yaml OrderCheckoutRequest.receiverAddress）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReceiverAddress {

    /** 收货人姓名 */
    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 64, message = "收货人姓名长度不能超过64")
    private String recipientName;

    /** 收货人手机号（大陆 11 位手机号） */
    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货人手机号格式不正确")
    private String phone;

    /** 详细收货地址 */
    @NotBlank(message = "详细收货地址不能为空")
    @Size(max = 512, message = "详细收货地址长度不能超过512")
    private String detailedAddress;
}
