package com.yuzhuang.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.admin.dto.AdminDtos.AdminUserResponse;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeGrantRequest;
import com.yuzhuang.admin.dto.AdminDtos.GovScopeResponse;
import com.yuzhuang.admin.dto.AdminDtos.PasswordResetResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantResponse;
import com.yuzhuang.admin.dto.AdminDtos.TenantUpsertRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserCreateRequest;
import com.yuzhuang.admin.dto.AdminDtos.UserStatusRequest;
import com.yuzhuang.admin.service.PlatformAdminService;
import com.yuzhuang.audit.service.AuditService;
import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.entity.User;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.auth.mapper.UserMapper;
import com.yuzhuang.auth.security.PasswordEncoder;
import com.yuzhuang.auth.security.PasswordPolicy;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.gov.entity.GovScope;
import com.yuzhuang.gov.mapper.GovScopeMapper;
import com.yuzhuang.tenant.entity.Tenant;
import com.yuzhuang.tenant.mapper.TenantMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台管理员服务实现。
 *
 * <p>安全约束：账号返回体永不含密码哈希；创建账号/重置密码使用一次性临时口令并置
 * {@code mustChangePassword=true}；所有变更写 {@link AuditService}。
 */
@Service
public class PlatformAdminServiceImpl implements PlatformAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final GovScopeMapper govScopeMapper;
    private final AuditService auditService;

    public PlatformAdminServiceImpl(TenantMapper tenantMapper, UserMapper userMapper,
                                    GovScopeMapper govScopeMapper, AuditService auditService) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.govScopeMapper = govScopeMapper;
        this.auditService = auditService;
    }

    @Override
    public List<TenantResponse> listTenants() {
        return tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                        .orderByAsc(Tenant::getId)).stream()
                .map(this::toTenantResponse)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantResponse createTenant(TenantUpsertRequest request) {
        String tenantId = request.tenantId().trim();
        Long exists = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId));
        if (exists > 0) {
            auditService.record("TENANT_CREATE", "TENANT", tenantId, "租户标识已存在", false);
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT, "租户标识已存在");
        }
        Tenant tenant = Tenant.builder()
                .tenantId(tenantId)
                .name(request.name().trim())
                .region(request.region())
                .tenantType(request.tenantType() == null || request.tenantType().isBlank()
                        ? "VILLAGE" : request.tenantType().trim().toUpperCase())
                .parentRegion(request.parentRegion())
                .status(STATUS_ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            tenantMapper.insert(tenant);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT, "租户标识已存在");
        }
        auditService.record("TENANT_CREATE", "TENANT", tenantId, "创建租户 " + tenant.getName(), true);
        return toTenantResponse(tenant);
    }

    @Override
    public List<AdminUserResponse> listUsers(String tenantId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().orderByAsc(User::getId);
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(User::getTenantId, tenantId.trim());
        }
        return userMapper.selectList(wrapper).stream().map(this::toUserResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserResponse createUser(UserCreateRequest request) {
        String username = request.username().trim();
        UserRole role = parseRole(request.role());
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (exists > 0) {
            auditService.record("USER_CREATE", "USER", username, "用户名已存在", false);
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT, "用户名已存在");
        }
        String violation = PasswordPolicy.validate(request.password(), username);
        if (violation != null) {
            throw new BusinessException(ResultCode.PASSWORD_POLICY_VIOLATION, violation);
        }
        User user = User.builder()
                .tenantId(request.tenantId().trim())
                .username(username)
                .passwordHash(PasswordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .role(role)
                .phone(request.phone())
                .status(STATUS_ACTIVE)
                .mustChangePassword(true)
                .failedLoginCount(0)
                .createdBy(currentUsername())
                .createdAt(LocalDateTime.now())
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT, "用户名已存在");
        }
        auditService.record("USER_CREATE", "USER", username,
                "创建账号 role=" + role + " tenant=" + user.getTenantId(), true);
        return toUserResponse(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserResponse updateUserStatus(Long userId, UserStatusRequest request) {
        User user = requireUser(userId);
        String status = request.status().trim().toUpperCase();
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态仅支持 ACTIVE / DISABLED");
        }
        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        if (STATUS_DISABLED.equals(status)) {
            update.setDisabledAt(LocalDateTime.now());
            update.setDisabledReason(request.reason());
        } else {
            update.setDisabledAt(null);
            update.setDisabledReason(null);
        }
        userMapper.updateById(update);
        user.setStatus(status);
        user.setDisabledAt(update.getDisabledAt());
        user.setDisabledReason(update.getDisabledReason());
        auditService.record("USER_STATUS", "USER", user.getUsername(),
                "账号状态更新为 " + status + "，原因：" + (request.reason() == null ? "-" : request.reason()), true);
        return toUserResponse(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordResetResponse resetPassword(Long userId) {
        User user = requireUser(userId);
        String tempPassword = generateTempPassword();
        User update = new User();
        update.setId(userId);
        update.setPasswordHash(PasswordEncoder.encode(tempPassword));
        update.setMustChangePassword(true);
        update.setFailedLoginCount(0);
        update.setLockedUntil(null);
        update.setPasswordUpdatedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
        auditService.record("USER_PASSWORD_RESET", "USER", user.getUsername(),
                "管理员重置密码（一次性临时口令，需首次改密）", true);
        return new PasswordResetResponse(user.getUsername(), tempPassword, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovScopeResponse grantScope(Long userId, GovScopeGrantRequest request) {
        User user = requireUser(userId);
        String scopeType = request.scopeType().trim().toUpperCase();
        if (!"ALL".equals(scopeType) && !"REGION".equals(scopeType) && !"TENANT".equals(scopeType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "范围类型仅支持 ALL / REGION / TENANT");
        }
        String scopeValue = "ALL".equals(scopeType) ? "*" : request.scopeValue().trim();
        Long exists = govScopeMapper.selectCount(new LambdaQueryWrapper<GovScope>()
                .eq(GovScope::getUserId, userId)
                .eq(GovScope::getScopeType, scopeType)
                .eq(GovScope::getScopeValue, scopeValue));
        if (exists > 0) {
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT, "该授权范围已存在");
        }
        GovScope scope = GovScope.builder()
                .tenantId("global")
                .userId(userId)
                .scopeType(scopeType)
                .scopeValue(scopeValue)
                .grantedBy(currentUsername())
                .createdAt(LocalDateTime.now())
                .build();
        govScopeMapper.insert(scope);
        auditService.record("GOV_SCOPE_GRANT", "USER", user.getUsername(),
                "授予范围 " + scopeType + "=" + scopeValue, true);
        return toScopeResponse(scope);
    }

    @Override
    public List<GovScopeResponse> listScopes(Long userId) {
        requireUser(userId);
        return govScopeMapper.selectList(new LambdaQueryWrapper<GovScope>()
                        .eq(GovScope::getUserId, userId).orderByAsc(GovScope::getId)).stream()
                .map(this::toScopeResponse).toList();
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户 ID 不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账号不存在");
        }
        return user;
    }

    private UserRole parseRole(String role) {
        try {
            return UserRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的角色：" + role);
        }
    }

    private String currentUsername() {
        return AuthContext.get() == null ? "system" : AuthContext.get().getUsername();
    }

    /** 生成 12 位一次性临时口令（含字母与数字，满足初始密码策略）。 */
    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        // 保证含数字（临时口令仍需满足策略，且强制首次改密）
        sb.setCharAt(0, (char) ('2' + RANDOM.nextInt(8)));
        return sb.toString();
    }

    private TenantResponse toTenantResponse(Tenant tenant) {
        return new TenantResponse(tenant.getId(), tenant.getTenantId(), tenant.getName(),
                tenant.getRegion(), tenant.getStatus(), tenant.getTenantType(),
                tenant.getParentRegion(), tenant.getCreatedAt());
    }

    private AdminUserResponse toUserResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), user.getRole() == null ? null : user.getRole().name(),
                user.getPhone(), user.getStatus(), user.getMustChangePassword(),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    private GovScopeResponse toScopeResponse(GovScope scope) {
        return new GovScopeResponse(scope.getId(), scope.getScopeType(), scope.getScopeValue(),
                scope.getGrantedBy(), scope.getCreatedAt());
    }
}



