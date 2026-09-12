package com.yuzhuang.channel.controller;

import com.yuzhuang.channel.adapter.ChannelAdapter;
import com.yuzhuang.channel.adapter.ChannelAdapterRegistry;
import com.yuzhuang.channel.adapter.ChannelSignatureVerifier;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.enums.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

/**
 * 外部渠道回调入口（阶段 G）。
 *
 * <p>行为约定（与「不得假装已接入」的验收要求一致）：
 * <ul>
 *   <li>渠道未在配置中登记 → 404 + A1004；</li>
 *   <li>渠道未启用/缺少密钥 → 503 + C5003，并说明未配置原因（不接收任何回调）；</li>
 *   <li>已启用但签名校验失败/过期 → 403 + A1003；</li>
 *   <li>签名校验通过 → 501 + C5004（订单回流逻辑待拿到真实凭证后实现，当前绝不落单）。</li>
 * </ul>
 * 该端点无需 JWT（由渠道签名认证），但仍受网关限流保护。
 */
@Slf4j
@Tag(name = "渠道回调", description = "外部销售渠道（抖音/快手/B2B）回调入口（默认未配置）")
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelNotifyController {

    private final ChannelAdapterRegistry registry;

    public ChannelNotifyController(ChannelAdapterRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "渠道订单回调（未配置渠道返回 503/C5003）")
    @PostMapping("/{channel}/notify")
    public ResponseEntity<ApiResponse<Void>> notify(
            @PathVariable("channel") String channel,
            @RequestHeader(value = "X-Channel-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Channel-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) {
        Optional<ChannelAdapter> adapterOpt = registry.find(channel);
        if (adapterOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail(ResultCode.NOT_FOUND.getCode(), "不支持的渠道：" + channel));
        }
        ChannelAdapter adapter = adapterOpt.get();
        if (!adapter.enabled()) {
            log.warn("[channel] notify rejected (not configured) channel={}", channel);
            return ResponseEntity.status(ResultCode.CHANNEL_NOT_CONFIGURED.getHttpStatus())
                    .body(ApiResponse.fail(ResultCode.CHANNEL_NOT_CONFIGURED.getCode(),
                            adapter.disabledReason()));
        }
        ChannelSignatureVerifier.Result result = adapter.verifySignature(
                timestamp, rawBody, signature, Instant.now().getEpochSecond());
        if (!result.ok()) {
            log.warn("[channel] signature rejected channel={}, result={}", channel, result);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.fail(ResultCode.FORBIDDEN.getCode(),
                            "渠道回调签名校验失败：" + result));
        }
        // 签名通过但订单回流尚未实现：明确返回未实现，绝不假装已受理
        log.warn("[channel] intake not implemented channel={}", channel);
        return ResponseEntity.status(ResultCode.CHANNEL_INTAKE_NOT_IMPLEMENTED.getHttpStatus())
                .body(ApiResponse.fail(ResultCode.CHANNEL_INTAKE_NOT_IMPLEMENTED.getCode(),
                        "渠道 " + channel + " 已通过签名校验，但订单回流逻辑尚未实现，请勿在生产启用"));
    }
}
