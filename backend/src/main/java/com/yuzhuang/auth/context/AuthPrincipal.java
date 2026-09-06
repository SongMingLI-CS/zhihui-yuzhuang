package com.yuzhuang.auth.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 认证主体（JWT 载荷与线程上下文中携带的用户身份）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthPrincipal {

    /** 用户主键 */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 所属租户标识 */
    private String tenantId;

    /** 角色名（FARMER / COOPERATIVE / VILLAGE） */
    private String role;

    /** 显示名称 */
    private String displayName;
}
