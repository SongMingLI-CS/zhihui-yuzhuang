package com.yuzhuang.channel.adapter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 渠道回调签名校验（阶段 G）。
 *
 * <p>签名算法：{@code HMAC-SHA256(secret, timestamp + "." + rawBody)} 的十六进制大写串，
 * 通过请求头 {@code X-Channel-Timestamp} 与 {@code X-Channel-Signature} 传递；
 * 时间戳偏移超过 {@link #MAX_SKEW_SECONDS} 秒视为过期，防重放。
 *
 * <p>未配置 {@code secret} 时一律视为“渠道未开通”，由适配器返回明确不可用，
 * <b>不</b>放行任何回调。
 */
public final class ChannelSignatureVerifier {

    /** 允许的时间戳偏移（秒） */
    public static final long MAX_SKEW_SECONDS = 300L;

    private ChannelSignatureVerifier() {
    }

    /**
     * 校验签名。
     *
     * @param secret    渠道密钥（为空视为未配置）
     * @param timestamp 请求头时间戳（epoch 秒，字符串）
     * @param rawBody   原始请求体
     * @param signature 请求头签名（十六进制，大小写不敏感）
     * @param nowEpochSeconds 当前时间（便于测试注入）
     * @return 校验结果
     */
    public static Result verify(String secret, String timestamp, String rawBody,
                                String signature, long nowEpochSeconds) {
        if (secret == null || secret.isBlank()) {
            return Result.NOT_CONFIGURED;
        }
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return Result.MISSING_HEADERS;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return Result.BAD_TIMESTAMP;
        }
        if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
            return Result.EXPIRED;
        }
        String expected = sign(secret, timestamp.trim(), rawBody == null ? "" : rawBody);
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
        return ok ? Result.OK : Result.BAD_SIGNATURE;
    }

    /** 生成签名（测试/联调自检用；生产由渠道方实现）。 */
    public static String sign(String secret, String timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + (rawBody == null ? "" : rawBody))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("渠道签名计算失败", ex);
        }
    }

    /** 校验结果。 */
    public enum Result {
        /** 校验通过 */
        OK,
        /** 渠道密钥未配置（视为未开通） */
        NOT_CONFIGURED,
        /** 缺少签名/时间戳请求头 */
        MISSING_HEADERS,
        /** 时间戳格式非法 */
        BAD_TIMESTAMP,
        /** 时间戳过期（超出允许偏移，防重放） */
        EXPIRED,
        /** 签名不匹配 */
        BAD_SIGNATURE;

        public boolean ok() {
            return this == OK;
        }
    }
}
