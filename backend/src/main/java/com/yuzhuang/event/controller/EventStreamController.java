package com.yuzhuang.event.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.event.dto.EventItem;
import com.yuzhuang.event.service.EventStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实业务事件流接口（阶段 D）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/events/recent}：按 id 游标拉取真实事件（轮询/首屏，JSON）；</li>
 *   <li>{@code GET /api/v1/events/stream}：SSE 长连接，数据源同为 Outbox 真实事件。</li>
 * </ul>
 * 二者均需有效会话（B 端同源 Cookie 或 Bearer），范围由 {@link EventStreamService}
 * 依据已验证主体与政府授权范围推导；<b>不存在</b>客户端模拟事件。
 */
@Slf4j
@Tag(name = "事件流", description = "真实业务事件（Outbox）轮询与 SSE 推送")
@RestController
@RequestMapping("/api/v1/events")
public class EventStreamController {

    /** SSE 单连接最长存活时间（毫秒）：到期正常结束，浏览器 EventSource 会自动重连。 */
    private static final long STREAM_TIMEOUT_MS = 5L * 60L * 1000L;
    /** 轮询间隔（秒）。 */
    private static final long POLL_SECONDS = 3L;
    /** 单次拉取上限。 */
    private static final int BATCH = 50;
    /** 心跳间隔（秒）：仅发送注释行，保持连接不被中间代理断开。 */
    private static final long HEARTBEAT_SECONDS = 20L;

    private final EventStreamService eventStreamService;
    private final ScheduledExecutorService eventStreamScheduler;

    public EventStreamController(EventStreamService eventStreamService,
                                 ScheduledExecutorService eventStreamScheduler) {
        this.eventStreamService = eventStreamService;
        this.eventStreamScheduler = eventStreamScheduler;
    }

    @Operation(summary = "按游标拉取真实业务事件（JSON 轮询）")
    @GetMapping("/recent")
    public ApiResponse<List<EventItem>> recent(
            @RequestParam(value = "afterId", required = false) Long afterId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(eventStreamService.recent(AuthContext.require(), afterId, limit));
    }

    @Operation(summary = "真实业务事件 SSE 流（5 分钟自动断开后由浏览器重连）")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        AuthPrincipal principal = AuthContext.require();
        // 在请求线程解析可见范围（ThreadLocal 不跨线程），随后仅传值给轮询任务
        Set<String> scope = eventStreamService.resolveScope(principal);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicLong cursor = new AtomicLong(0L);

        ScheduledFuture<?> task = eventStreamScheduler.scheduleWithFixedDelay(() -> {
            try {
                List<EventItem> items = eventStreamService.recentByScope(scope, 
                        cursor.get() == 0L ? null : cursor.get(), BATCH);
                if (cursor.get() == 0L) {
                    // 首轮：推最近一批，并把游标推进到最大 id（避免下一轮重复推送）
                    for (EventItem item : items) {
                        emitter.send(SseEmitter.event().name("event").data(item));
                    }
                    if (!items.isEmpty()) {
                        cursor.set(items.get(items.size() - 1).getId());
                    }
                } else {
                    for (EventItem item : items) {
                        emitter.send(SseEmitter.event().name("event").data(item));
                        cursor.set(item.getId());
                    }
                    emitter.send(SseEmitter.event().comment("hb"));
                }
            } catch (IOException | IllegalStateException ex) {
                // 客户端断开：结束连接
                emitter.complete();
            } catch (Exception ex) { // noqa: BLE001 - 单次轮询异常不应中断连接
                log.warn("[events] stream poll failed tenant={}", principal.getTenantId(), ex);
            }
        }, 0L, POLL_SECONDS, TimeUnit.SECONDS);

        Runnable stop = () -> task.cancel(true);
        emitter.onCompletion(stop);
        emitter.onTimeout(() -> {
            stop.run();
            emitter.complete();
        });
        emitter.onError(e -> stop.run());
        return emitter;
    }
}
