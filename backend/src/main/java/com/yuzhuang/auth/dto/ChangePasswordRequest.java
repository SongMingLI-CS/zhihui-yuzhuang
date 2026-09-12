package com.yuzhuang.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 修改密码请求（首次登录强制改密 / 主动改密）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    /** 当前密码（首次登录场景为演示/临时口令） */
    @NotBlank(message = "当前密码不能为空")
    @Size(min = 6, max = 128, message = "当前密码长度需在 6~128 之间")
    private String currentPassword;

    /** 新密码（≥8 位，需含字母与数字） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 128, message = "新密码长度需在 8~128 之间")
    private String newPassword;
}
