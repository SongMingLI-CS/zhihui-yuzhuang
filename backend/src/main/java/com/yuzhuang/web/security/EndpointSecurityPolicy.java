package com.yuzhuang.web.security;

import com.yuzhuang.auth.enums.UserRole;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;

/**
 * 端点级访问策略（显式声明，替代“任意 GET 均可读”的粗粒度规则）。
 *
 * <p>契约来源：docs/project-audit-and-full-repair-prompt-2026-09-12.md 阶段 B
 * 与 docs/api-spec.yaml。要点：
 * <ul>
 *   <li><b>PUBLIC</b>：匿名可访问（健康探针、登录、C 端商品读/下单）；</li>
 *   <li><b>AUTHENTICATED</b>：任意有效令牌（本人信息、改密）；</li>
 *   <li><b>ROLES</b>：仅列出的角色可访问（其余 403 + A1003）；</li>
 *   <li>未命中任何规则的 {@code /api/v1/**}：默认要求已认证（deny-by-default）。</li>
 * </ul>
 *
 * <p>策略只做“能否调用该端点”的门禁；行级数据范围（本租户 / 政府授权区域 / global 目录）
 * 由各业务服务基于已验证主体推导，见会话与授权范围服务。
 * <b>前端隐藏按钮不构成授权，本策略 + 服务层才是最终权限源。</b>
 */
@Component
public class EndpointSecurityPolicy {

    /** 访问级别。 */
    public enum Access {
        /** 匿名公开 */
        PUBLIC,
        /** 任意已认证角色 */
        AUTHENTICATED,
        /** 限定角色集合 */
        ROLES
    }

    /** 端点规则（HTTP 方法 + Ant 路径 + 访问级别 + 允许角色）。 */
    public record Rule(String method, String pattern, Access access, Set<String> roles) {

        boolean matches(String actualMethod, String actualPath, AntPathMatcher matcher) {
            return method.equalsIgnoreCase(actualMethod) && matcher.match(pattern, actualPath);
        }
    }

    private static final Set<String> EMPTY = Set.of();

    /** 运营/管理角色（可维护商品、订单履约；不含农户与政府）。 */
    private static final Set<String> OPS = Set.of(
            UserRole.COOPERATIVE.name(), UserRole.VILLAGE.name(), UserRole.PLATFORM_ADMIN.name());

    /** 治理/经营看板可读者（村委、商家、政府、平台；排除农户）。 */
    private static final Set<String> DASHBOARD = Set.of(
            UserRole.VILLAGE.name(), UserRole.COOPERATIVE.name(),
            UserRole.GOVERNMENT.name(), UserRole.PLATFORM_ADMIN.name());

    private static final Set<String> GOVERNMENT = Set.of(
            UserRole.GOVERNMENT.name(), UserRole.PLATFORM_ADMIN.name());

    private static final Set<String> PLATFORM_ADMIN = Set.of(UserRole.PLATFORM_ADMIN.name());

    /** 有序规则表（首条命中生效）。 */
    private static final List<Rule> RULES = List.of(
            // ---------- 匿名公开 ----------
            new Rule("GET", "/api/v1/healthz", Access.PUBLIC, EMPTY),
            new Rule("POST", "/api/v1/auth/login", Access.PUBLIC, EMPTY),
            new Rule("POST", "/api/v1/auth/logout", Access.PUBLIC, EMPTY),
            new Rule("GET", "/api/v1/auth/csrf", Access.PUBLIC, EMPTY),
            new Rule("GET", "/api/v1/products", Access.PUBLIC, EMPTY),
            new Rule("GET", "/api/v1/products/*", Access.PUBLIC, EMPTY),
            new Rule("POST", "/api/v1/orders/checkout", Access.PUBLIC, EMPTY),
            new Rule("GET", "/api/v1/shops/**", Access.PUBLIC, EMPTY),
            // ---------- 消费者订单查询凭证（仅凭订单号 + 查询口令；服务层校验，见阶段 E）
            new Rule("POST", "/api/v1/orders/guest/lookup", Access.PUBLIC, EMPTY),
            new Rule("POST", "/api/v1/orders/guest/cancel", Access.PUBLIC, EMPTY),
            // 外部渠道回调（由渠道签名认证，非 JWT；未配置渠道返回 503/C5003）
            new Rule("POST", "/api/v1/channels/*/notify", Access.PUBLIC, EMPTY),

            // ---------- 任意已认证 ----------
            new Rule("GET", "/api/v1/auth/me", Access.AUTHENTICATED, EMPTY),
            new Rule("POST", "/api/v1/auth/password/change", Access.AUTHENTICATED, EMPTY),

            // ---------- 经营大盘（农户不可读）----------
            new Rule("GET", "/api/v1/dashboard/summary", Access.ROLES, DASHBOARD),

            // ---------- 商品管理写（商家/村委/平台）----------
            new Rule("POST", "/api/v1/products", Access.ROLES, OPS),
            new Rule("PUT", "/api/v1/products/*", Access.ROLES, OPS),
            new Rule("PATCH", "/api/v1/products/*/status", Access.ROLES, OPS),

            // ---------- 商家工作台（含草稿/下架商品可见）----------
            new Rule("GET", "/api/v1/merchant/**", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/merchant/**", Access.ROLES, OPS),
            new Rule("PUT", "/api/v1/merchant/**", Access.ROLES, OPS),
            new Rule("PATCH", "/api/v1/merchant/**", Access.ROLES, OPS),
            new Rule("DELETE", "/api/v1/merchant/**", Access.ROLES, OPS),

            // ---------- 订单查询与履约（农户不可读全租户订单）----------
            new Rule("GET", "/api/v1/orders", Access.ROLES, OPS),
            new Rule("GET", "/api/v1/orders/*", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/orders/*/ship", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/orders/*/mark-ready", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/orders/*/recover", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/orders/*/cancel", Access.ROLES, OPS),
            new Rule("POST", "/api/v1/orders/*/pay/sandbox", Access.ROLES, OPS),

            // ---------- 政府治理（只读聚合/导出）----------
            new Rule("GET", "/api/v1/gov/**", Access.ROLES, GOVERNMENT),

            // ---------- 平台管理（租户/账号/角色/配置）----------
            new Rule("GET", "/api/v1/admin/**", Access.ROLES, PLATFORM_ADMIN),
            new Rule("POST", "/api/v1/admin/**", Access.ROLES, PLATFORM_ADMIN),
            new Rule("PUT", "/api/v1/admin/**", Access.ROLES, PLATFORM_ADMIN),
            new Rule("PATCH", "/api/v1/admin/**", Access.ROLES, PLATFORM_ADMIN),
            new Rule("DELETE", "/api/v1/admin/**", Access.ROLES, PLATFORM_ADMIN)
    );

    private final AntPathMatcher matcher = new AntPathMatcher();

    /**
     * 解析端点策略。
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @return 命中的规则；未命中返回 {@code null}（调用方按“默认需认证”处理）
     */
    public Rule resolve(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.matches(method, path, matcher)) {
                return rule;
            }
        }
        return null;
    }
}
