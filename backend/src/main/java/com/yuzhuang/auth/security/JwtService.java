package com.yuzhuang.auth.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 签发与校验（HS256，JDK 内置 HMAC-SHA256 + Base64Url，零额外依赖）。
 *
 * <p>令牌结构：{@code base64url(header).base64url(payload).base64url(signature)}；
 * 载荷携带 {@code sub / userId / tenantId / role / displayName / iat / exp}。
 * 校验依次做：分段数 → 签名常量时间比对 → 过期判断。
 */
@Component
public class JwtService {

    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secret;
    private final long expireSeconds;
    private final ObjectMapper objectMapper;

    public JwtService(@Value("${yuzhuang.auth.secret}") String secret,
                      @Value("${yuzhuang.auth.expire-minutes:120}") long expireMinutes,
                      ObjectMapper objectMapper) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("yuzhuang.auth.secret 未配置");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireSeconds = expireMinutes * 60L;
        this.objectMapper = objectMapper;
    }

    /** 令牌有效期（秒）。 */
    public long getExpireSeconds() {
        return expireSeconds;
    }

    /** 依据认证主体签发 JWT。 */
    public String generateToken(AuthPrincipal principal) {
        long now = Instant.now().getEpochSecond();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", principal.getUsername());
        payload.put("userId", principal.getUserId());
        payload.put("tenantId", principal.getTenantId());
        payload.put("role", principal.getRole());
        payload.put("displayName", principal.getDisplayName());
        payload.put("iat", now);
        payload.put("exp", now + expireSeconds);

        String headerB64 = base64Url(writeJson(header));
        String payloadB64 = base64Url(writeJson(payload));
        String signingInput = headerB64 + "." + payloadB64;
        return signingInput + "." + base64Url(sign(signingInput));
    }

    /** 解析并校验令牌，返回认证主体；无效令牌抛 {@link IllegalArgumentException}。 */
    public AuthPrincipal parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("令牌为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("令牌格式非法");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput);
        byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("令牌签名无效");
        }

        Map<String, Object> payload = readJson(Base64.getUrlDecoder().decode(parts[1]));
        long exp = ((Number) payload.get("exp")).longValue();
        if (exp < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("令牌已过期");
        }

        return AuthPrincipal.builder()
                .userId(((Number) payload.get("userId")).longValue())
                .username((String) payload.get("sub"))
                .tenantId((String) payload.get("tenantId"))
                .role((String) payload.get("role"))
                .displayName((String) payload.get("displayName"))
                .build();
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new IllegalStateException("JWT 载荷序列化失败", e);
        }
    }

    private Map<String, Object> readJson(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌载荷解析失败", e);
        }
    }
}
