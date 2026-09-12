package com.yuzhuang.admin.service;

import com.yuzhuang.admin.dto.AdminDtos.AdminUserResponse;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeGrantRequest;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeResponse;
import com.yuzhuang.admin.dto.AdminDtos.PasswordResetResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantUpsertRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserCreateRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserStatusRequest;

import java.util.List;

/**
 * 平台管理员服务（租户 / 账号 / 角色 / 启停 / 重置 / 授权范围）。
 *
 * <p>仅 {@code PLATFORM_ADMIN} 可调用（由 {@code EndpointSecurityPolicy} 在
 * {@code /api/v1/admin/**} 强制）；所有变更写审计日志。
 */
public interface PlatformAdminService {

    /** 租户列表。 */
    List<TenantResponse> listTenants();

    /** 创建租户（已存在则冲突）。 */
    TenantResponse createTenant(TenantUpsertRequest request);

    /** 账号列表（可按租户过滤，不返回密码哈希）。 */
    List<AdminUserResponse> listUsers(String tenantId);

    /** 创建账号（初始口令 + 首次登录改密）。 */
    AdminUserResponse createUser(UserCreateRequest request);

    /** 启用/停用账号。 */
    AdminUserResponse updateUserStatus(Long userId, UserStatusRequest request);

    /** 重置账号密码，返回一次性临时口令（并要求首次改密）。 */
    PasswordResetResponse resetPassword(Long userId);

    /** 授予政府/平台账号数据范围。 */
    GovScopeResponse grantScope(Long userId, GovScopeGrantRequest request);

    /** 查询账号授权范围。 */
    List<GovScopeResponse> listScopes(Long userId);
}
