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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox 可靠投递器（发布端带租约认领，多实例安全）。
 *
 * <p>每 {@code scan-interval-ms} 扫描一批<b>可认领</b>事件（PENDING、retry&lt;max 且未认领/租约过期）：
 * <ol>
 *   <li>{@code claimById} 条件 UPDATE 原子认领 → 只有恰好一个实例获得租约；</li>
 *   <li>获得租约者 XADD → CAS 流转 PENDING → PUBLISHED 并清空租约（先入流、后确认）；</li>
 *   <li>发布失败 → retry_count+1 并清空租约（可立即续投），达上限置 FAILED；认领后实例崩溃 → 租约到期自动接管。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yuzhuang.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OutboxSweeper {

    private final StringRedisTemplate redisTemplate;
    private final OutboxEventMapper outboxEventMapper;
    private final int batchSize;
    private final int maxRetry;
    private final long leaseSeconds;
    private final String instanceId;

    @Autowired
    public OutboxSweeper(StringRedisTemplate redisTemplate,
                         OutboxEventMapper outboxEventMapper,
                         @Value("${yuzhuang.outbox.batch-size:50}") int batchSize,
                         @Value("${yuzhuang.outbox.max-retry:5}") int maxRetry,
                         @Value("${yuzhuang.outbox.lease-seconds:60}") long leaseSeconds,
                         @Value("${yuzhuang.outbox.instance-id:}") String instanceId) {
        this.redisTemplate = redisTemplate;
        this.outboxEventMapper = outboxEventMapper;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.leaseSeconds = leaseSeconds;
        this.instanceId = (instanceId == null || instanceId.isBlank())
                ? "instance-" + UUID.randomUUID() : instanceId.trim();
    }

    /** 测试/手动触发便捷构造：batch=50、maxRetry=5、lease=60s、实例标识随机。 */
    public OutboxSweeper(StringRedisTemplate redisTemplate, OutboxEventMapper outboxEventMapper) {
        this(redisTemplate, outboxEventMapper, 50, 5, 60, null);
    }

    /**
     * 定时扫描入口（@EnableScheduling 驱动；多实例由 DB 租约保证单事件仅一个实例发布）。
     */
    @Scheduled(fixedDelayString = "${yuzhuang.outbox.scan-interval-ms:2000}")
    public void scheduledSweep() {
        publishPendingBatch(batchSize, maxRetry);
    }
    /**
     * 扫描并投递一批待发事件，返回成功发布（流转为 PUBLISHED）的条数。
     * 公开纯函数式入口，便于定时任务与单元/集成测试复用。
     */
    public int publishPendingBatch(int batchSize, int maxRetry) {
        List<OutboxEvent> events;
        try {
            events = outboxEventMapper.selectClaimableBatch(batchSize, maxRetry, LocalDateTime.now());
        } catch (Exception e) {
            log.error("[outbox-sweeper] select claimable batch failed, instance={}", instanceId, e);
            return 0;
        }
        if (events.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : events) {
            LocalDateTime claimedAt = LocalDateTime.now();
            // 条件 UPDATE 原子认领：并发/多实例下只有一个成功；0 = 租约被其他实例持有
            int claimed = outboxEventMapper.claimById(event.getId(), claimedAt,
                    claimedAt.plusSeconds(leaseSeconds), instanceId);
            if (claimed != 1) {
                continue;
            }
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
            log.info("[outbox-sweeper] instance={} published {} outbox event(s) to stream: {}",
                    instanceId, published, RedisStreamConstants.STREAM_ORDER_EVENTS);
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
     * 发布失败处理：原子累加重试次数并清空租约；达上限置 FAILED 并告警，否则保持
     * PENDING 等待下一轮续投。此处不回滚/删除消息 —— at-least-once + 幂等消费兜底。
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
