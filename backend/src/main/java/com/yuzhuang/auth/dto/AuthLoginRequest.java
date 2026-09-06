package com.yuzhuang.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 登录请求（严格对齐 docs/api-spec.yaml AuthLoginRequest）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginRequest {

    /** 登录用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 64, message = "用户名长度需在 2~64 之间")
    private String username;

    /** 登录密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 128, message = "密码长度需在 6~128 之间")
    private String password;
}
