package com.yuzhuang.test;

import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Web 契约测试支撑：为受保护端点签发指定角色/租户的 JWT（Bearer 头）。
 *
 * <p>说明：自安全强化起，B 端受保护端点（订单查询/履约、商品管理写）的租户与角色
 * 一律取自 JWT，不再读 {@code X-Tenant-Id} 头；因此端点测试统一携带 Authorization 头，
 * 由 {@code JwtAuthenticationFilter} → {@code AuthGuardInterceptor} 完成认证/鉴权闭环。
 */
public abstract class WebAuthTestSupport {

    public static final String TENANT_A = "tenant_yuzhuang_001";
    public static final String TENANT_B = "tenant_yuzhuang_002";

    @Autowired
    protected JwtService jwtService;

    /** 构造指定角色 + 租户的认证主体（服务层直接测试需手动写入 AuthContext）。 */
    protected AuthPrincipal principal(String role, String tenantId) {
        return AuthPrincipal.builder()
                .userId(1L)
                .username("tester")
                .tenantId(tenantId)
                .role(role)
                .displayName("契约测试账号")
                .build();
    }

    /** 签发指定角色 + 租户的 Bearer 令牌头。 */
    protected String bearer(String role, String tenantId) {
        return "Bearer " + jwtService.generateToken(principal(role, tenantId));
    }

    protected String villageBearer() {
        return bearer("VILLAGE", TENANT_A);
    }

    protected String cooperativeBearer() {
        return bearer("COOPERATIVE", TENANT_A);
    }

    protected String farmerBearer() {
        return bearer("FARMER", TENANT_A);
    }
}
