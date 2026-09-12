package com.yuzhuang.auth.filter;

/**
 * Cookie 名称常量（会话与 CSRF，默认值与 application.yml 保持一致）。
 */
public final class CookieNames {

    /** 会话 Cookie（HttpOnly）。 */
    public static final String SESSION = "yz_session";

    /** CSRF 双提交 Cookie（非 HttpOnly，前端可读）。 */
    public static final String CSRF = "yz_csrf";

    private CookieNames() {
    }
}
