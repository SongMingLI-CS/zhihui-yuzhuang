package com.yuzhuang.web.security;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证/授权强制拦截器（登记路径 {@code /api/v1/**}，见 {@code WebMvcConfig}）。
 *
 * <p>策略来源：{@link EndpointSecurityPolicy}（显式端点×角色矩阵）。
 * 行为：
 * <ul>
 *   <li>命中 PUBLIC → 放行；</li>
 *   <li>命中 AUTHENTICATED → 需有效令牌，否则 401 + A1002；</li>
 *   <li>命中 ROLES → 令牌角色不在白名单 → 403 + A1003；</li>
 *   <li>未命中任何规则 → 默认要求已认证（deny-by-default），杜绝“新增端点默认开放”。</li>
 * </ul>
 *
 * <p>本拦截器只做端点门禁；行级数据范围由业务服务基于 {@link AuthContext#require()} 推导。
 */
@Component
public class AuthGuardInterceptor implements HandlerInterceptor {

    private final EndpointSecurityPolicy policy;

    public AuthGuardInterceptor(EndpointSecurityPolicy policy) {
        this.policy = policy;
    }

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

        EndpointSecurityPolicy.Rule rule = policy.resolve(method, path);
        if (rule != null && rule.access() == EndpointSecurityPolicy.Access.PUBLIC) {
            return true;
        }

        AuthPrincipal principal = AuthContext.get();
        if (principal == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (rule != null && rule.access() == EndpointSecurityPolicy.Access.ROLES
                && !rule.roles().contains(principal.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        // rule == null（未显式声明）→ 仅要求已认证；ROLES 命中且角色合法 → 放行
        return true;
    }
}

