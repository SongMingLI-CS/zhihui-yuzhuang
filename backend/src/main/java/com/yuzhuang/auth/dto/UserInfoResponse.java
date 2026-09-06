package com.yuzhuang.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 登录用户信息（对齐 docs/api-spec.yaml AuthLoginResponse.user）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    /** 用户主键 */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 显示名称 */
    private String displayName;

    /** 所属租户标识 */
    private String tenantId;

    /** 角色名（FARMER / COOPERATIVE / VILLAGE） */
    private String role;
}
