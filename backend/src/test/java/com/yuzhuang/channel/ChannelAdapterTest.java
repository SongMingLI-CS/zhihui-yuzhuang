package com.yuzhuang.channel;

import com.yuzhuang.channel.adapter.ChannelAdapter;
import com.yuzhuang.channel.adapter.ChannelAdapterRegistry;
import com.yuzhuang.channel.adapter.ChannelProperties;
import com.yuzhuang.channel.adapter.ChannelSignatureVerifier;
import com.yuzhuang.order.enums.OrderSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道适配器与签名校验单元测试（阶段 G，无需 Spring / 数据库）。
 *
 * <p>覆盖：默认未配置即 disabled、密钥缺失不可用、签名有效/错误/过期/缺头、
 * 未配置渠道不得放行回调。
 */
class ChannelAdapterTest {

    private static final long NOW = 1_800_000_000L;

    private ChannelAdapterRegistry registry(boolean enabled, String secret) {
        return new ChannelAdapterRegistry(new ChannelProperties(
                enabled, secret, false, "", false, ""));
    }

    @Test
    void defaultConfigIsDisabledForAllChannels() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(new ChannelProperties(
                false, "", false, "", false, ""));
        assertThat(registry.enabledStates())
                .containsEntry("DOUYIN", false)
                .containsEntry("KUAISHOU", false)
                .containsEntry("B2B_PORTAL", false);
        assertThat(registry.find("douyin")).isPresent()
                .get().satisfies(a -> {
                    assertThat(a.enabled()).isFalse();
                    assertThat(a.disabledReason()).contains("未启用");
                });
    }

    @Test
    void enabledWithoutSecretIsStillUnusable() {
        ChannelAdapter adapter = registry(true, "").find("DOUYIN").orElseThrow();
        assertThat(adapter.enabled()).isFalse();
        assertThat(adapter.disabledReason()).contains("密钥");
        assertThat(adapter.verifySignature("1", "{}", "SIG", NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.NOT_CONFIGURED);
    }

    @Test
    void unknownChannelReturnsEmpty() {
        ChannelAdapterRegistry registry = registry(false, "");
        assertThat(registry.find("pinduoduo")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("  ")).isEmpty();
    }

    @Test
    void signatureVerification_happyPathAndTampering() {
        String secret = "channel-secret-abc";
        String body = "{\"orderNo\":\"DY-1\"}";
        String ts = String.valueOf(NOW);
        String sig = ChannelSignatureVerifier.sign(secret, ts, body);

        assertThat(ChannelSignatureVerifier.verify(secret, ts, body, sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.OK);
        // 大小写不敏感
        assertThat(ChannelSignatureVerifier.verify(secret, ts, body, sig.toLowerCase(), NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.OK);
        // 篡改请求体
        assertThat(ChannelSignatureVerifier.verify(secret, ts, body + "x", sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.BAD_SIGNATURE);
        // 密钥不对
        assertThat(ChannelSignatureVerifier.verify("other-secret", ts, body, sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.BAD_SIGNATURE);
    }

    @Test
    void signatureVerification_rejectsExpiredAndMissingHeaders() {
        String secret = "channel-secret-abc";
        String ts = String.valueOf(NOW);
        String sig = ChannelSignatureVerifier.sign(secret, ts, "{}");

        assertThat(ChannelSignatureVerifier.verify(secret, String.valueOf(NOW - 3600), "{}", sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.EXPIRED);
        assertThat(ChannelSignatureVerifier.verify(secret, ts, "{}", "not-a-signature", NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.BAD_SIGNATURE);
        assertThat(ChannelSignatureVerifier.verify(secret, null, "{}", sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.MISSING_HEADERS);
        assertThat(ChannelSignatureVerifier.verify(secret, "abc", "{}", sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.BAD_TIMESTAMP);
        assertThat(ChannelSignatureVerifier.verify("", ts, "{}", sig, NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.NOT_CONFIGURED);
    }

    @Test
    void configuredAdapterVerifiesSignatureWhenEnabled() {
        String secret = "channel-secret-xyz";
        ChannelAdapter adapter = registry(true, secret).find(OrderSource.DOUYIN.name()).orElseThrow();
        assertThat(adapter.enabled()).isTrue();
        String ts = String.valueOf(NOW);
        String body = "{\"orderNo\":\"DY-2\"}";
        assertThat(adapter.verifySignature(ts, body, ChannelSignatureVerifier.sign(secret, ts, body), NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.OK);
        assertThat(adapter.verifySignature(ts, body, "BAD", NOW))
                .isEqualTo(ChannelSignatureVerifier.Result.BAD_SIGNATURE);
    }
}
