package com.yuzhuang.channel.adapter;

import com.yuzhuang.order.enums.OrderSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于配置的渠道适配器（阶段 G）。
 *
 * <p>当前实现只做「开通状态 + 签名校验」，订单回流（落单/幂等/库存扣减）待拿到真实凭证后再接入；
 * 未开通时对外一律返回明确错误，绝不假装成功。
 */
public class ConfiguredChannelAdapter implements ChannelAdapter {

    private final OrderSource channel;
    private final ChannelProperties.ChannelConfig config;

    public ConfiguredChannelAdapter(OrderSource channel, ChannelProperties.ChannelConfig config) {
        this.channel = channel;
        this.config = config;
    }

    @Override
    public OrderSource channel() {
        return channel;
    }

    @Override
    public boolean enabled() {
        return config.usable();
    }

    @Override
    public String disabledReason() {
        if (!config.enabled()) {
            return "渠道 " + channel + " 未启用（YuzhuangChannelEnabled=false）";
        }
        if (config.secret() == null || config.secret().isBlank()) {
            return "渠道 " + channel + " 缺少回调签名密钥（secret 未配置）";
        }
        return "渠道 " + channel + " 未配置";
    }

    @Override
    public ChannelSignatureVerifier.Result verifySignature(String timestamp, String rawBody,
                                                           String signature, long nowEpochSeconds) {
        if (!enabled()) {
            return ChannelSignatureVerifier.Result.NOT_CONFIGURED;
        }
        return ChannelSignatureVerifier.verify(config.secret(), timestamp, rawBody, signature, nowEpochSeconds);
    }
}
