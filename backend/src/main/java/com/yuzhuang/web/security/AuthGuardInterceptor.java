package com.yuzhuang.web.security;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 认证/授权强制拦截器（登记路径 {@code /api/v1/**}，见 {@code WebMvcConfig}）。
 *
 * <p>对齐 docs/api-spec.yaml 与 docs/audit-verification.md §三 的访问矩阵：
 * <ul>
 *   <li><b>匿名公开</b>：登录、健康探针、商品读、C 端下单（{@link #PUBLIC}）；</li>
 *   <li><b>任意已认证</b>：GET/HEAD 只读端点（数据域=JWT tenantId）；</li>
 *   <li><b>COOPERATIVE / VILLAGE</b>：写端点（商品上架/编辑/上下架、订单履约推进）；</li>
 *   <li>其余（未命中公开白名单）一律要求有效 JWT，未认证 → 401 + A1002，越权 → 403 + A1003。</li>
 * </ul>
 *
 * <p>该拦截器只做<b>认证与角色门禁</b>；数据作用域（本租户/global 目录规则）由各业务服务按
 * {@link AuthContext#require()} 的 tenantId/role 校验，见商品写服务。
 */
@Component
public class AuthGuardInterceptor implements HandlerInterceptor {

    /** 端点规则（HTTP 方法 + Ant 路径）。 */
    private record Rule(String method, String pattern) {
        boolean matches(String actualMethod, String actualPath) {
            return method.equalsIgnoreCase(actualMethod)
                    && new AntPathMatcher().match(pattern, actualPath);
        }
    }

    /** 匿名公开端点（不做任何令牌校验）。 */
    private static final Set<Rule> PUBLIC = Set.of(
            new Rule("GET", "/api/v1/healthz"),
            new Rule("POST", "/api/v1/auth/login"),
            new Rule("GET", "/api/v1/products"),
            new Rule("GET", "/api/v1/products/*"),
            new Rule("POST", "/api/v1/orders/checkout"));

    private static final String ROLE_COOPERATIVE = UserRole.COOPERATIVE.name();
    private static final String ROLE_VILLAGE = UserRole.VILLAGE.name();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true; // 非控制器（静态资源等）放行
        }
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true; // CORS 预检放行
        }
        String path = request.getRequestURI();

        if (isPublic(method, path)) {
            return true;
        }

        AuthPrincipal principal = AuthContext.get();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!isReadOnly(method) && !hasManagerRole(principal)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return true;
    }

    private boolean isPublic(String method, String path) {
        for (Rule rule : PUBLIC) {
            if (rule.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    /** GET/HEAD 视为只读：任意已认证角色可访问（数据域由 token tenantId 限定）。 */
    private boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 管理写角色：合作社运营（COOPERATIVE）/ 村委运营（VILLAGE）。 */
    private boolean hasManagerRole(AuthPrincipal principal) {
        String role = principal.getRole();
        return ROLE_COOPERATIVE.equals(role) || ROLE_VILLAGE.equals(role);
    }
}
