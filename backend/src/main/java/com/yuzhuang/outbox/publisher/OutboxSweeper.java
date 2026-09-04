package com.yuzhuang.outbox.publisher;

import com.yuzhuang.infrastructure.redis.RedisStreamConstants;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.enums.OutboxStatus;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox → Redis Streams 定时投递器。
 *
 * <p>每 {@code scan-interval-ms}（默认 2s）扫描一批 {@code status='PENDING'}
 * 且 {@code retry_count < max-retry}（默认 50 条 / 上限 5 次）的事件，
 * 逐条 {@code XADD} 到订单事件 Stream：
 * <ul>
 *   <li>发布成功 → 状态 CAS 流转 {@code PENDING → PUBLISHED}（先入流、后确认，
 *       保证 DB 的 PUBLISHED 语义为"确实已入流"）；</li>
 *   <li>发布失败 → 原子 {@code retry_count+1}，达上限置 {@code FAILED} 并告警日志，
 *       否则保持 {@code PENDING} 由下一轮扫描续投（At-least-once）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yuzhuang.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OutboxSweeper {

    private final StringRedisTemplate redisTemplate;
    private final OutboxEventMapper outboxEventMapper;
    private final int batchSize;
    private final int maxRetry;

    @Autowired
    public OutboxSweeper(StringRedisTemplate redisTemplate,
                         OutboxEventMapper outboxEventMapper,
                         @Value("${yuzhuang.outbox.batch-size:50}") int batchSize,
                         @Value("${yuzhuang.outbox.max-retry:5}") int maxRetry) {
        this.redisTemplate = redisTemplate;
        this.outboxEventMapper = outboxEventMapper;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    /** 测试/手动触发便捷构造：batchSize=50、maxRetry=5。 */
    public OutboxSweeper(StringRedisTemplate redisTemplate, OutboxEventMapper outboxEventMapper) {
        this(redisTemplate, outboxEventMapper, 50, 5);
    }

    /**
     * 定时扫描入口（由 @EnableScheduling 驱动，fixedDelay 保证上一轮完成后间隔触发，
     * 多实例并发部署时不会互相踩踏单条记录：发布确认与失败登记均带 PENDING 条件 CAS）。
     */
    @Scheduled(fixedDelayString = "${yuzhuang.outbox.scan-interval-ms:2000}")
    public void scheduledSweep() {
        publishPendingBatch(batchSize, maxRetry);
    }

    /**
     * 扫描并投递一批待发事件，返回成功发布（流转为 PUBLISHED）的条数。
     * 该方法为公开纯函数式入口，便于定时任务与单元/集成测试复用。
     */
    public int publishPendingBatch(int batchSize, int maxRetry) {
        List<OutboxEvent> events;
        try {
            events = outboxEventMapper.selectPendingBatch(batchSize, maxRetry);
        } catch (Exception e) {
            log.error("[outbox-sweeper] select pending batch failed", e);
            return 0;
        }
        if (events.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : events) {
            try {
                xadd(event);
                int updated = outboxEventMapper.markPublished(event.getId());
                if (updated == 1) {
                    published++;
                }
            } catch (Exception e) {
                handlePublishFailure(event, maxRetry, e);
            }
        }

        if (published > 0) {
            log.info("[outbox-sweeper] published {} outbox event(s) to stream: {}",
                    published, RedisStreamConstants.STREAM_ORDER_EVENTS);
        }
        return published;
    }

    /** 将单条事件 XADD 写入订单事件 Stream（hash 字段：eventId / payload）。 */
    private void xadd(OutboxEvent event) {
        String id = String.valueOf(event.getId());
        String payload = event.getPayload() == null ? "" : event.getPayload();
        Map<String, String> fields = Map.of(
                RedisStreamConstants.FIELD_EVENT_ID, id,
                RedisStreamConstants.FIELD_PAYLOAD, payload);
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        streamOps.add(RedisStreamConstants.STREAM_ORDER_EVENTS, fields);
    }

    /**
     * 发布失败处理：原子累加重试次数；达上限置 FAILED 并输出告警日志，否则保持
     * PENDING 等待下一轮续投。此处不回滚/删除消息 —— 由 at-least-once + 幂等消费兜底。
     */
    private void handlePublishFailure(OutboxEvent event, int maxRetry, Exception cause) {
        try {
            outboxEventMapper.recordPublishFailure(event.getId(), maxRetry);
            OutboxEvent reloaded = outboxEventMapper.selectById(event.getId());
            int retryCount = reloaded != null && reloaded.getRetryCount() != null ? reloaded.getRetryCount() : -1;
            if (reloaded != null && OutboxStatus.FAILED.name().equals(reloaded.getStatus())) {
                log.error("[outbox-sweeper] ALERT: outbox event FAILED after {} retries, id={}, eventType={}, "
                        + "aggregateId={}, tenantId={}",
                        retryCount, event.getId(), event.getEventType(), event.getAggregateId(), event.getTenantId());
            } else {
                log.warn("[outbox-sweeper] XADD failed, will retry next sweep, id={}, retryCount={}, cause={}",
                        event.getId(), retryCount, cause.toString());
            }
        } catch (Exception dbEx) {
            log.error("[outbox-sweeper] record publish failure failed, id={}", event.getId(), dbEx);
        }
    }
}
