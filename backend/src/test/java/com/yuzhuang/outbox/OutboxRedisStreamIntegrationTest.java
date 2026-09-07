package com.yuzhuang.outbox;

import com.yuzhuang.infrastructure.redis.RedisStreamConstants;
import com.yuzhuang.order.consumer.OrderEventStreamConsumer;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.enums.OutboxStatus;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import com.yuzhuang.outbox.publisher.OutboxSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.time.LocalDateTime;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox → Redis Streams 异步链路集成测试（Mockito 隔离，test profile + H2）。
 *
 * <p>测试 profile 已关闭真实后台链路（yuzhuang.outbox.enabled=false），因此本测试
 * 手动装配 {@link OutboxSweeper} / {@link OrderEventStreamConsumer}，以 Mockito 模拟
 * Redis（StringRedisTemplate / StreamOperations），Mapper 使用真实 H2，验证：
 * <ul>
 *   <li>扫描 → XADD → PENDING 流转 PUBLISHED，且载荷（eventId/payload）确实入流；</li>
 *   <li>瞬时失败自动重试（retry_count+1 保持 PENDING），恢复后发布成功；</li>
 *   <li>持续失败达到 maxRetry 后置 FAILED 且不再被扫描（告警路径）；</li>
 *   <li>消费者解析 → 派单打点 → 幂等回写 PROCESSED → ACK；</li>
 *   <li>端到端 PENDING → PUBLISHED → PROCESSED，全链路无死锁、无残留。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxRedisStreamIntegrationTest {

    private static final String TENANT_ID = "tenant_yuzhuang_001";
    private static final String STREAM = RedisStreamConstants.STREAM_ORDER_EVENTS;
    private static final String GROUP = RedisStreamConstants.GROUP_ORDER_DISPATCH;
    private static final String DEFAULT_BATCH = "50";
    private static final String DEFAULT_MAX_RETRY = "5";

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @BeforeEach
    void cleanTable() {
        outboxEventMapper.delete(null);
    }

    // ============================================================
    // A. 发布：扫描 PENDING → XADD 入流 → 流转 PUBLISHED
    // ============================================================

    @Test
    @DisplayName("扫描 PENDING 事件 → XADD 写入 Stream → 状态流转 PUBLISHED，载荷完整")
    void sweep_pendingEvent_xaddsToStream_andMarksPublished() {
        OutboxEvent seeded = seedOutbox("PENDING", 0, "ORD-A-0001");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap())).thenReturn(RecordId.of("1750000000001-0"));

        OutboxSweeper sweeper = new OutboxSweeper(redis, outboxEventMapper);
        int published = sweeper.publishPendingBatch(Integer.parseInt(DEFAULT_BATCH), Integer.parseInt(DEFAULT_MAX_RETRY));

        assertThat(published).isEqualTo(1);

        // 校验写入流中的载荷字段
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> addedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq(STREAM), addedCaptor.capture());
        Map<String, String> added = addedCaptor.getValue();
        assertThat(added).containsEntry(RedisStreamConstants.FIELD_EVENT_ID, String.valueOf(seeded.getId()));
        assertThat(added).containsEntry(RedisStreamConstants.FIELD_PAYLOAD, seeded.getPayload());

        // 发布成功后状态确认
        OutboxEvent reloaded = outboxEventMapper.selectById(seeded.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
    }

    // ============================================================
    // B. 重试：瞬时失败 retry_count+1 保持 PENDING，恢复后成功
    // ============================================================

    @Test
    @DisplayName("瞬时 XADD 异常 → 重试计数 +1 仍 PENDING；恢复后发布成功")
    void sweep_transientFailure_incrementsRetry_thenSuccessPublishes() {
        OutboxEvent seeded = seedOutbox("PENDING", 0, "ORD-B-0002");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap()))
                .thenThrow(new RedisConnectionFailureException("simulated transient outage"))
                .thenReturn(RecordId.of("1750000000002-0"));

        OutboxSweeper sweeper = new OutboxSweeper(redis, outboxEventMapper);

        // 第一轮扫描：投递失败 → retry_count 0->1，保持 PENDING
        assertThat(sweeper.publishPendingBatch(50, 5)).isZero();
        OutboxEvent afterFail = outboxEventMapper.selectById(seeded.getId());
        assertThat(afterFail.getStatus()).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(afterFail.getRetryCount()).isEqualTo(1);

        // 第二轮扫描：恢复 → 发布成功 → PUBLISHED
        assertThat(sweeper.publishPendingBatch(50, 5)).isEqualTo(1);
        OutboxEvent afterOk = outboxEventMapper.selectById(seeded.getId());
        assertThat(afterOk.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
        assertThat(afterOk.getRetryCount()).isEqualTo(1);
    }

    // ============================================================
    // C. 达上限：持续失败 → FAILED 且不再被扫描
    // ============================================================

    @Test
    @DisplayName("持续失败达到 maxRetry=5 → 置 FAILED(retry_count=5)，不再被扫描")
    void sweep_persistentFailure_reachesMaxRetry_marksFailedAndExcludes() {
        // 已重试 4 次，本轮失败将触发第 5 次并达上限
        OutboxEvent seeded = seedOutbox("PENDING", 4, "ORD-C-0003");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap())).thenThrow(new RedisConnectionFailureException("redis down"));

        OutboxSweeper sweeper = new OutboxSweeper(redis, outboxEventMapper);
        assertThat(sweeper.publishPendingBatch(50, 5)).isZero();

        OutboxEvent reloaded = outboxEventMapper.selectById(seeded.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.FAILED.name());
        assertThat(reloaded.getRetryCount()).isEqualTo(5);

        // 已达上限：不再出现在待投递批次（避免无限重试拖垮）
        assertThat(outboxEventMapper.selectClaimableBatch(50, 5, LocalDateTime.now())).isEmpty();
    }

    // ============================================================
    // D. 消费：解析 → 派单打点 → 幂等回写 PROCESSED → ACK
    // ============================================================

    @Test
    @DisplayName("消费者处理 PUBLISHED 事件 → 派单打点 + 回写 PROCESSED + 向 Stream ACK")
    void consumer_onMessage_dispatches_marksProcessed_andAcks() {
        OutboxEvent seeded = seedOutbox("PUBLISHED", 0, "ORD-D-0004");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class))).thenReturn(1L);

        RecordId recordId = RecordId.of("1750000000004-0");
        Map<String, Object> fields = new HashMap<>();
        fields.put(RedisStreamConstants.FIELD_EVENT_ID, String.valueOf(seeded.getId()));
        fields.put(RedisStreamConstants.FIELD_PAYLOAD, seeded.getPayload());
        MapRecord<String, String, Object> message = MapRecord.create(STREAM, fields).withId(recordId);

        OrderEventStreamConsumer consumer = new OrderEventStreamConsumer(redis, outboxEventMapper);
        consumer.onMessage(message);

        assertThat(outboxEventMapper.selectById(seeded.getId()).getStatus())
                .isEqualTo(OutboxStatus.PROCESSED.name());
        verify(streamOps).acknowledge(eq(STREAM), eq(GROUP), eq(recordId));
    }

    // ============================================================
    // E. 端到端：PENDING →(sweep) PUBLISHED →(consumer) PROCESSED
    // ============================================================

    @Test
    @DisplayName("端到端：PENDING → 发布 PUBLISHED → 消费 PROCESSED，全链路无残留")
    void endToEnd_pending_publish_consume_noDeadlock() {
        OutboxEvent seeded = seedOutbox("PENDING", 0, "ORD-E-0005");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap())).thenReturn(RecordId.of("1750000000005-0"));
        when(streamOps.acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class))).thenReturn(1L);

        // 1) Sweeper 发布
        OutboxSweeper sweeper = new OutboxSweeper(redis, outboxEventMapper);
        assertThat(sweeper.publishPendingBatch(50, 5)).isEqualTo(1);
        assertThat(outboxEventMapper.selectById(seeded.getId()).getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED.name());

        // 2) 捕获已入流载荷，模拟消费者从 Stream 读到的记录
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> addedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq(STREAM), addedCaptor.capture());
        Map<String, String> added = addedCaptor.getValue();
        MapRecord<String, String, Object> message =
                MapRecord.create(STREAM, new HashMap<String, Object>(added))
                        .withId(RecordId.of("1750000000005-0"));

        // 3) 消费者处理并 ACK
        OrderEventStreamConsumer consumer = new OrderEventStreamConsumer(redis, outboxEventMapper);
        consumer.onMessage(message);

        // 4) 状态闭环：PROCESSED 且不再有残留 PENDING/PUBLISHED（无死锁）
        assertThat(outboxEventMapper.selectById(seeded.getId()).getStatus())
                .isEqualTo(OutboxStatus.PROCESSED.name());
        assertThat(outboxEventMapper.selectClaimableBatch(50, 5, LocalDateTime.now())).isEmpty();
        verify(streamOps).acknowledge(eq(STREAM), eq(GROUP), eq(message.getId()));
    }

    // ============================================================
    // F. 发布端租约：多实例防重复 XADD / 认领崩溃后租约到期接管
    // ============================================================

    @Test
    @DisplayName("实例A持有有效租约 → 实例B扫描跳过（不重复 XADD）")
    void lease_heldByOtherInstance_secondInstanceSkips() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent held = seedOutboxClaimed("PENDING", 0, "ORD-L-0006",
                now, now.plusMinutes(2), "instance-A");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap())).thenReturn(RecordId.of("1750000000006-0"));

        OutboxSweeper sweeperB = new OutboxSweeper(redis, outboxEventMapper);
        assertThat(sweeperB.publishPendingBatch(50, 5)).isZero();
        verify(streamOps, never()).add(eq(STREAM), anyMap());
        assertThat(outboxEventMapper.selectById(held.getId()).getStatus())
                .isEqualTo(OutboxStatus.PENDING.name());
    }

    @Test
    @DisplayName("认领实例崩溃且租约过期 → 实例B自动接管并发布（消息不丢）")
    void lease_expiredAfterCrashedInstance_secondInstancePublishes() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent expired = seedOutboxClaimed("PENDING", 0, "ORD-L-0007",
                now.minusMinutes(5), now.minusMinutes(4), "instance-A");

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(eq(STREAM), anyMap())).thenReturn(RecordId.of("1750000000007-0"));

        OutboxSweeper sweeperB = new OutboxSweeper(redis, outboxEventMapper);
        assertThat(sweeperB.publishPendingBatch(50, 5)).isEqualTo(1);
        verify(streamOps).add(eq(STREAM), anyMap());
        OutboxEvent reloaded = outboxEventMapper.selectById(expired.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
        assertThat(reloaded.getInstanceId()).isNull();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private OutboxEvent seedOutbox(String status, int retryCount, String orderNo) {
        String payload = "{\"eventType\":\"ORDER_CREATED\",\"orderNo\":\"" + orderNo
                + "\",\"tenantId\":\"" + TENANT_ID + "\",\"status\":\"CREATED\"}";
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(TENANT_ID)
                .aggregateType("ORDER")
                .aggregateId(orderNo)
                .eventType("ORDER_CREATED")
                .payload(payload)
                .status(status)
                .retryCount(retryCount)
                .build();
        outboxEventMapper.insert(event);
        return event;
    }

    /** 带租约认领字段的种子事件（模拟其他实例已认领 / 认领后崩溃）。 */
    private OutboxEvent seedOutboxClaimed(String status, int retryCount, String orderNo,
                                          LocalDateTime claimedAt, LocalDateTime leaseUntil, String instanceId) {
        OutboxEvent event = seedOutbox(status, retryCount, orderNo);
        event.setClaimedAt(claimedAt);
        event.setLeaseUntil(leaseUntil);
        event.setInstanceId(instanceId);
        outboxEventMapper.updateById(event);
        return event;
    }
}
