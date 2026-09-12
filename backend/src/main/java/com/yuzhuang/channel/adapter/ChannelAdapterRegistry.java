package com.yuzhuang.channel.adapter;

import com.yuzhuang.order.enums.OrderSource;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道适配器注册表（阶段 G）。
 *
 * <p>当前登记三类外部渠道：抖音（DOUYIN）、快手（KUAISHOU）、B2B 集采（B2B_PORTAL）；
 * 默认全部未开通，可通过环境变量配置后启用签名校验。
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<OrderSource, ChannelAdapter> adapters;

    public ChannelAdapterRegistry(ChannelProperties properties) {
        Map<OrderSource, ChannelAdapter> map = new EnumMap<>(OrderSource.class);
        for (Map.Entry<OrderSource, ChannelProperties.ChannelConfig> entry : properties.all().entrySet()) {
            map.put(entry.getKey(), new ConfiguredChannelAdapter(entry.getKey(), entry.getValue()));
        }
        this.adapters = map;
    }

    /** 按渠道名解析适配器（大小写不敏感）；未知渠道返回空。 */
    public Optional<ChannelAdapter> find(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return Optional.empty();
        }
        try {
            OrderSource source = OrderSource.valueOf(channelName.trim().toUpperCase());
            return Optional.ofNullable(adapters.get(source));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** 渠道开通状态（供平台/运维展示：已配置/未配置，不含密钥）。 */
    public Map<String, Boolean> enabledStates() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        adapters.forEach((source, adapter) -> states.put(source.name(), adapter.enabled()));
        return states;
    }
}
