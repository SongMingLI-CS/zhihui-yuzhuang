package com.yuzhuang.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 平台管理 API 的数据传输对象集合（阶段 B：租户/账号/角色/授权范围管理）。
 *
 * <p>绝不返回 {@code password_hash}；新账号/重置密码使用一次性临时口令并要求首次改密。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** 创建/更新租户请求。 */
    public record TenantUpsertRequest(
            @NotBlank(message = "租户标识不能为空") @Size(max = 64) String tenantId,
            @NotBlank(message = "租户名称不能为空") @Size(max = 128) String name,
            @Size(max = 255) String region,
            @Size(max = 32) String tenantType,
            @Size(max = 128) String parentRegion) {
    }

    /** 创建账号请求。 */
    public record UserCreateRequest(
            @NotBlank(message = "租户标识不能为空") @Size(max = 64) String tenantId,
            @NotBlank(message = "用户名不能为空") @Size(min = 3, max = 64) String username,
            @NotBlank(message = "初始密码不能为空") @Size(min = 8, max = 128) String password,
            @NotBlank(message = "显示名称不能为空") @Size(max = 64) String displayName,
            @NotBlank(message = "角色不能为空") @Size(max = 32) String role,
            @Size(max = 20) String phone) {
    }

    /** 账号启停请求。 */
    public record UserStatusRequest(
            @NotBlank(message = "状态不能为空") String status,
            @Size(max = 255) String reason) {
    }

    /** 政府/平台授权范围授予请求。 */
    public record GovScopeGrantRequest(
            @NotBlank(message = "范围类型不能为空") String scopeType,
            @NotBlank(message = "范围取值不能为空") @Size(max = 128) String scopeValue) {
    }

    /** 租户响应。 */
    public record TenantResponse(
            Long id, String tenantId, String name, String region, String status,
            String tenantType, String parentRegion, LocalDateTime createdAt) {
    }

    /** 账号响应（不含密码哈希）。 */
    public record AdminUserResponse(
            Long id, String tenantId, String username, String displayName, String role,
            String phone, String status, Boolean mustChangePassword,
            LocalDateTime lastLoginAt, LocalDateTime createdAt) {
    }

    /** 密码重置响应（一次性临时口令）。 */
    public record PasswordResetResponse(
            String username, String temporaryPassword, boolean mustChangePassword) {
    }

    /** 授权范围响应。 */
    public record GovScopeResponse(
            Long id, String scopeType, String scopeValue, String grantedBy, LocalDateTime createdAt) {
    }
}
