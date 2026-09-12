package com.yuzhuang.admin.controller;

import com.yuzhuang.admin.dto.AdminDtos.AdminUserResponse;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeGrantRequest;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeResponse;
import com.yuzhuang.admin.dto.AdminDtos.PasswordResetResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantUpsertRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserCreateRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserStatusRequest;
import com.yuzhuang.admin.service.PlatformAdminService;
import com.yuzhuang.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理接口（仅 {@code PLATFORM_ADMIN}，由端点安全策略强制）。
 *
 * <p>契约（对齐 docs/api-spec.yaml 平台管理段）：
 * <ul>
 *   <li>{@code GET/POST /api/v1/admin/tenants}：租户列表 / 创建；</li>
 *   <li>{@code GET/POST /api/v1/admin/users}：账号列表 / 创建（初始口令 + 首次改密）；</li>
 *   <li>{@code PATCH /api/v1/admin/users/{id}/status}：启用/停用；</li>
 *   <li>{@code POST /api/v1/admin/users/{id}/password/reset}：重置为一次性临时口令；</li>
 *   <li>{@code GET/POST /api/v1/admin/users/{id}/scopes}：政府/平台数据授权范围。</li>
 * </ul>
 */
@Slf4j
@Tag(name = "平台管理", description = "租户/账号/角色/授权范围管理（PLATFORM_ADMIN）")
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformAdminController {

    private final PlatformAdminService platformAdminService;

    public PlatformAdminController(PlatformAdminService platformAdminService) {
        this.platformAdminService = platformAdminService;
    }

    @Operation(summary = "租户列表")
    @GetMapping("/tenants")
    public ApiResponse<List<TenantResponse>> listTenants() {
        return ApiResponse.success(platformAdminService.listTenants());
    }

    @Operation(summary = "创建租户")
    @PostMapping("/tenants")
    public ApiResponse<TenantResponse> createTenant(@Valid @RequestBody TenantUpsertRequest request) {
        return ApiResponse.success(platformAdminService.createTenant(request));
    }

    @Operation(summary = "账号列表（可按租户过滤，不返回密码）")
    @GetMapping("/users")
    public ApiResponse<List<AdminUserResponse>> listUsers(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.success(platformAdminService.listUsers(tenantId));
    }

    @Operation(summary = "创建账号（初始口令需首次改密）")
    @PostMapping("/users")
    public ApiResponse<AdminUserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.success(platformAdminService.createUser(request));
    }

    @Operation(summary = "启用/停用账号")
    @PatchMapping("/users/{id}/status")
    public ApiResponse<AdminUserResponse> updateStatus(
            @PathVariable("id") Long id, @Valid @RequestBody UserStatusRequest request) {
        return ApiResponse.success(platformAdminService.updateUserStatus(id, request));
    }

    @Operation(summary = "重置账号密码（返回一次性临时口令）")
    @PostMapping("/users/{id}/password/reset")
    public ApiResponse<PasswordResetResponse> resetPassword(@PathVariable("id") Long id) {
        return ApiResponse.success(platformAdminService.resetPassword(id));
    }

    @Operation(summary = "查询账号数据授权范围")
    @GetMapping("/users/{id}/scopes")
    public ApiResponse<List<GovScopeResponse>> listScopes(@PathVariable("id") Long id) {
        return ApiResponse.success(platformAdminService.listScopes(id));
    }

    @Operation(summary = "授予账号数据授权范围（ALL/REGION/TENANT）")
    @PostMapping("/users/{id}/scopes")
    public ApiResponse<GovScopeResponse> grantScope(
            @PathVariable("id") Long id, @Valid @RequestBody GovScopeGrantRequest request) {
        return ApiResponse.success(platformAdminService.grantScope(id, request));
    }
}
