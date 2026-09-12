package com.yuzhuang.order.support;

import com.yuzhuang.auth.security.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本人订单查询凭证（阶段 E）。
 *
 * <p>匿名消费者下单时服务端下发一次性查询凭证，库中仅保存 PBKDF2 哈希；
 * 后续查询必须同时提供「订单号 + 查询凭证」，避免仅凭订单号即可读取他人订单与收货信息。
 */
public final class QueryTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

    private QueryTokens() {
    }

    /** 生成 32 字符左右的 URL 安全随机凭证。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 生成凭证哈希（PBKDF2，含随机盐）。 */
    public static String hash(String token) {
        return token == null ? null : PasswordEncoder.encode(token);
    }

    /** 常量时间校验凭证。 */
    public static boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return PasswordEncoder.matches(token, storedHash);
    }
}
