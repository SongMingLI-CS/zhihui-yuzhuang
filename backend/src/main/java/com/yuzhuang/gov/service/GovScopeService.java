package com.yuzhuang.gov.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.gov.entity.GovScope;
import com.yuzhuang.gov.mapper.GovScopeMapper;
import com.yuzhuang.tenant.entity.Tenant;
import com.yuzhuang.tenant.mapper.TenantMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 政府/平台授权范围服务。
 *
 * <p>把“角色”与“可读数据范围”解耦：{@code GOVERNMENT} 只读权限仍需
 * {@code t_gov_scope} 显式授权；{@code PLATFORM_ADMIN} 具备全局范围；
 * {@code VILLAGE} 仅限本租户治理。
 *
 * <p>所有受保护请求的 tenantId 一律由本服务基于已验证主体推导，
 * <b>绝不</b>信任 {@code X-Tenant-Id} 头 / 表单 tenant_id / 请求体 tenantId。
 */
@Service
public class GovScopeService {

    /** 范围类型常量。 */
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_REGION = "REGION";
    public static final String SCOPE_TENANT = "TENANT";

    private final GovScopeMapper govScopeMapper;
    private final TenantMapper tenantMapper;

    public GovScopeService(GovScopeMapper govScopeMapper, TenantMapper tenantMapper) {
        this.govScopeMapper = govScopeMapper;
        this.tenantMapper = tenantMapper;
    }

    /** 授权范围解析结果。 */
    public record ScopeResolution(boolean all, Set<String> tenantIds, String description) {

        /** 命中租户是否在授权范围内。 */
        public boolean allows(String tenantId) {
            return all || (tenantId != null && tenantIds.contains(tenantId));
        }
    }

    /** 解析当前主体的可治理租户范围。 */
    public ScopeResolution resolve(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String role = principal.getRole();
        if (UserRole.PLATFORM_ADMIN.name().equals(role)) {
            return new ScopeResolution(true, Set.of(), "平台管理员：全平台");
        }
        if (UserRole.VILLAGE.name().equals(role)) {
            return new ScopeResolution(false, Set.of(principal.getTenantId()), "村委：本租户");
        }
        if (!UserRole.GOVERNMENT.name().equals(role)) {
            // 其他角色不拥有治理聚合范围
            return new ScopeResolution(false, Set.of(), "无治理授权");
        }

        List<GovScope> scopes = govScopeMapper.selectList(new LambdaQueryWrapper<GovScope>()
                .eq(GovScope::getUserId, principal.getUserId()));
        if (scopes.isEmpty()) {
            return new ScopeResolution(false, Set.of(), "政府账号未授权任何范围");
        }

        boolean all = false;
        Set<String> tenantIds = new LinkedHashSet<>();
        for (GovScope scope : scopes) {
            String type = scope.getScopeType() == null ? "" : scope.getScopeType().toUpperCase();
            String value = scope.getScopeValue();
            switch (type) {
                case SCOPE_ALL -> all = true;
                case SCOPE_TENANT -> {
                    if (value != null && !value.isBlank()) {
                        tenantIds.add(value.trim());
                    }
                }
                case SCOPE_REGION -> tenantIds.addAll(tenantsByRegion(value));
                default -> { /* 未知范围类型忽略 */ }
            }
        }
        String desc = all ? "政府：全区域（显式 ALL 授权）"
                : "政府：授权 " + tenantIds.size() + " 个租户";
        return new ScopeResolution(all, tenantIds, desc);
    }

    /** 按区域解析租户集合（region 或 parent_region 命中）。 */
    private Set<String> tenantsByRegion(String region) {
        Set<String> result = new LinkedHashSet<>();
        if (region == null || region.isBlank()) {
            return result;
        }
        String value = region.trim();
        List<Tenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>());
        for (Tenant tenant : tenants) {
            boolean match = value.equals(tenant.getParentRegion())
                    || (tenant.getRegion() != null && tenant.getRegion().contains(value));
            if (match) {
                result.add(tenant.getTenantId());
            }
        }
        return result;
    }
}
