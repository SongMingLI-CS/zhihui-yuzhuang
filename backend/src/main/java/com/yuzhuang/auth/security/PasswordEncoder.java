package com.yuzhuang.auth.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 密码哈希工具（PBKDF2WithHmacSHA256，JDK 内置，零额外依赖）。
 *
 * <p>编码格式：{@code pbkdf2$<iterations>$<saltHex>$<hashHex>}，采用常量时间比较防时序攻击。
 */
public final class PasswordEncoder {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "pbkdf2$";

    private PasswordEncoder() {
    }

    /** 生成带随机盐的密码哈希。 */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + toHex(salt) + "$" + toHex(hash);
    }

    /** 校验明文密码与已编码哈希是否匹配（常量时间）。 */
    public static boolean matches(String rawPassword, String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return false;
        }
        String[] parts = encoded.substring(PREFIX.length()).split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = fromHex(parts[1]);
            byte[] expected = fromHex(parts[2]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static byte[] derive(String raw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希计算失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
