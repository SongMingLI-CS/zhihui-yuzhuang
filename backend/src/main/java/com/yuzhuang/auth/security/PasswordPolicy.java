package com.yuzhuang.auth.security;

import java.util.Set;

/**
 * 密码强度策略（账号安全，服务端强制）。
 *
 * <p>规则：长度 ≥ 8；同时包含字母与数字；不得等于用户名；不得命中常见弱口令清单。
 * 命中任一违规由 {@code AuthServiceImpl} 抛 {@code A1006}。
 */
public final class PasswordPolicy {

    /** 常见弱口令（小写比对）。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "password", "passw0rd", "12345678", "123456789", "1234567890",
            "qwerty123", "admin123", "admin888", "coop1234", "abc12345",
            "yuzhuang", "iloveyou", "11111111", "88888888");

    private PasswordPolicy() {
    }

    /**
     * 校验新密码；不满足时返回错误描述，满足返回 {@code null}。
     *
     * @param rawPassword 明文新密码
     * @param username    账号名（用于禁止同名口令）
     * @return 违规描述或 {@code null}
     */
    public static String validate(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.length() < 8) {
            return "新密码长度至少 8 位";
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "新密码需同时包含字母与数字";
        }
        if (username != null && rawPassword.equalsIgnoreCase(username)) {
            return "新密码不得与用户名相同";
        }
        if (WEAK_PASSWORDS.contains(rawPassword.toLowerCase())) {
            return "新密码过于简单，请更换";
        }
        return null;
    }
}
