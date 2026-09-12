package com.yuzhuang.channel.adapter;

import com.yuzhuang.order.enums.OrderSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道配置（阶段 G）：以「启用开关 + 密钥」描述每个外部渠道。
 *
 * <p>安全默认：**全部默认关闭且密钥为空**。密钥留空时视为未开通，
 * 即便误把 enabled 打开也不会放行回调（见 {@link ConfiguredChannelAdapter}）。
 * 密钥仅来自环境变量 / secret，不写入仓库，也不下发给前端。
 */
@Component
public class ChannelProperties {

    /** 单个渠道配置。 */
    public record ChannelConfig(boolean enabled, String secret) {

        /** 是否真正可用（开关打开且密钥非空）。 */
        public boolean usable() {
            return enabled && secret != null && !secret.isBlank();
        }
    }

    private final Map<OrderSource, ChannelConfig> configs;

    public ChannelProperties(
            @Value("${yuzhuang.channel.douyin.enabled:false}") boolean douyinEnabled,
            @Value("${yuzhuang.channel.douyin.secret:}") String douyinSecret,
            @Value("${yuzhuang.channel.kuaishou.enabled:false}") boolean kuaishouEnabled,
            @Value("${yuzhuang.channel.kuaishou.secret:}") String kuaishouSecret,
            @Value("${yuzhuang.channel.b2b.enabled:false}") boolean b2bEnabled,
            @Value("${yuzhuang.channel.b2b.secret:}") String b2bSecret) {
        Map<OrderSource, ChannelConfig> map = new LinkedHashMap<>();
        map.put(OrderSource.DOUYIN, new ChannelConfig(douyinEnabled, douyinSecret));
        map.put(OrderSource.KUAISHOU, new ChannelConfig(kuaishouEnabled, kuaishouSecret));
        map.put(OrderSource.B2B_PORTAL, new ChannelConfig(b2bEnabled, b2bSecret));
        this.configs = Map.copyOf(map);
    }

    /** 查询渠道配置；未声明的渠道返回“关闭且无密钥”。 */
    public ChannelConfig config(OrderSource channel) {
        return configs.getOrDefault(channel, new ChannelConfig(false, ""));
    }

    public Optional<ChannelConfig> find(OrderSource channel) {
        return Optional.ofNullable(configs.get(channel));
    }

    /** 所有已声明渠道（供运维/能力接口展示“已配置/未配置”）。 */
    public Map<OrderSource, ChannelConfig> all() {
        return configs;
    }
}
