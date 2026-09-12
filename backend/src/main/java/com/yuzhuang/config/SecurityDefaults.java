package com.yuzhuang.config;

/**
 * 已知的开发期默认密钥/口令清单。
 *
 * <p>生产启动校验会拒绝这些取值，避免 {@code application.yml} 中的开发默认值被带上生产。
 * 该清单仅用于“拒绝”，不用于任何“回退到默认”的语义。
 */
public final class SecurityDefaults {

    /** application.yml 中的开发默认 JWT 密钥（与 yuzhuang.auth.secret 默认值一致）。 */
    public static final String DEV_JWT_SECRET = "yuzhuang-demo-jwt-secret-0123456789abcdef";

    /** 生产环境 JWT 密钥最小长度（高熵要求）。 */
    public static final int MIN_PRODUCTION_SECRET_LENGTH = 32;

    private SecurityDefaults() {
    }
}
