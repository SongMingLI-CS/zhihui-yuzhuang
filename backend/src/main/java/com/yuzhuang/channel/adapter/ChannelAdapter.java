package com.yuzhuang.channel.adapter;

import com.yuzhuang.order.enums.OrderSource;

/**
 * 外部销售渠道适配器（阶段 G）。
 *
 * <p>设计目标：把「抖音 / 快手 / B2B 集采」的订单回流收敛到统一接口，
 * 使渠道未开通时的行为<b>可预期且可测试</b>（明确 disabled，而不是假装已接入）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #enabled()} 为 false 时，任何回调都必须被拒绝（返回明确错误）；</li>
 *   <li>{@link #verifySignature} 由各渠道实现签名算法（统一走
 *       {@link ChannelSignatureVerifier} 的 HMAC-SHA256 + 时间窗）；</li>
 *   <li>幂等键为渠道侧订单号 {@code externalOrderNo}（落库时与 tenant 组合唯一）。</li>
 * </ul>
 */
public interface ChannelAdapter {

    /** 渠道标识（与 {@link OrderSource} 枚举名一致）。 */
    OrderSource channel();

    /** 是否已配置并启用（未配置必须返回 false）。 */
    boolean enabled();

    /** 未启用原因（用于对外错误消息与运维排查）。 */
    String disabledReason();

    /**
     * 校验回调签名。
     *
     * @param timestamp 请求头时间戳（epoch 秒）
     * @param rawBody   原始请求体
     * @param signature 请求头签名
     * @param nowEpochSeconds 当前时间（便于测试注入）
     * @return 校验结果
     */
    ChannelSignatureVerifier.Result verifySignature(String timestamp, String rawBody,
                                                    String signature, long nowEpochSeconds);
}
