package com.yuzhuang.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 登录响应（严格对齐 docs/api-spec.yaml AuthLoginResponse）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResponse {

    /** JWT 访问令牌（Authorization: Bearer <token>） */
    private String token;

    /** 令牌类型 */
    private String tokenType;

    /** 令牌有效期（秒） */
    private long expiresIn;

    /** 是否要求下次登录改密（演示账号/管理员重置后为 true，前端应跳转改密页） */
    private boolean mustChangePassword;

    /** 登录用户信息 */
    private UserInfoResponse user;
}
