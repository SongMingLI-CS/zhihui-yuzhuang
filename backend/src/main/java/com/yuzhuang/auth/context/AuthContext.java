package com.yuzhuang.auth.context;

/**
 * 认证线程上下文。
 *
 * <p>由 {@code JwtAuthenticationFilter} 从 {@code Authorization: Bearer <token>}
 * 解析有效令牌后写入，供业务层判断当前登录用户；缺省（无令牌/匿名）为 null。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 写入当前线程认证主体。 */
    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    /** 获取当前线程认证主体；未认证返回 null。 */
    public static AuthPrincipal get() {
        return HOLDER.get();
    }

    /** 是否已认证。 */
    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    /** 清理（过滤器 finally 中调用）。 */
    public static void clear() {
        HOLDER.remove();
    }
}
