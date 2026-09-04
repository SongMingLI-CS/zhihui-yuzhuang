package com.yuzhuang.order.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.infrastructure.redis.RedisStreamConstants;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订单事件 Stream 消费者（订阅组 {@code group_order_dispatch}）。
 *
 * <p>At-least-once 投递下的消费语义：
 * <ul>
 *   <li>解析事件 → 执行派单/发货就绪业务（当前为日志打点模拟，真实实现接入仓储/物流子系统）；</li>
 *   <li>幂等回写 {@code Outbox → PROCESSED}（仅 PUBLISHED/PENDING 可流转，重复投递返回 0 无副作用）；</li>
 *   <li>最后向 Stream ACK：只有业务成功才 ACK，异常不回 ACK（消息保留 Pending Entries List，
 *       由容器/后续轮次重新投递）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yuzhuang.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OrderEventStreamConsumer implements StreamListener<String, MapRecord<String, String, Object>> {

    private final StringRedisTemplate redisTemplate;
    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public OrderEventStreamConsumer(StringRedisTemplate redisTemplate,
                                    OutboxEventMapper outboxEventMapper,
                                    ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /** 测试/手动触发便捷构造：使用默认 ObjectMapper。 */
    public OrderEventStreamConsumer(StringRedisTemplate redisTemplate, OutboxEventMapper outboxEventMapper) {
        this(redisTemplate, outboxEventMapper, new ObjectMapper());
    }

    @Override
    public void onMessage(MapRecord<String, String, Object> message) {
        String recordId = message.getId() != null ? message.getId().getValue() : "-";
        Map<String, Object> fields = message.getValue();
        if (fields == null || fields.isEmpty()) {
            log.error("[order-consumer] empty message body, ACK to avoid poison-pill, recordId={}", recordId);
            acknowledge(message);
            return;
        }

        Object eventIdValue = fields.get(RedisStreamConstants.FIELD_EVENT_ID);
        if (eventIdValue == null) {
            log.error("[order-consumer] missing eventId field, ACK to avoid poison-pill, recordId={}", recordId);
            acknowledge(message);
            return;
        }

        Long eventId;
        try {
            eventId = Long.valueOf(String.valueOf(eventIdValue));
        } catch (NumberFormatException e) {
            log.error("[order-consumer] invalid eventId=[{}], ACK to avoid poison-pill, recordId={}",
                    eventIdValue, recordId);
            acknowledge(message);
            return;
        }

        Object payload = fields.get(RedisStreamConstants.FIELD_PAYLOAD);
        try {
            // ---- 派单业务：当前为打点模拟（真实实现：合作社自提 / 物流发货就绪单下发仓储调度子系统） ----
            String orderNo = resolveOrderNo(payload);
            log.info("已为订单 {} 生成合作社自提/物流发货就绪单，outboxEventId={}，streamRecordId={}",
                    orderNo, eventId, recordId);

            // 幂等回写：仅 PUBLISHED/PENDING → PROCESSED；At-least-once 重放时返回 0，无重复副作用
            outboxEventMapper.markProcessed(eventId);

            // 业务成功才 ACK：从本组 Pending Entries List 移除，避免重复投递
            acknowledge(message);
            log.info("[order-consumer] processed & ACKed outboxEventId={}, recordId={}", eventId, recordId);
        } catch (Exception e) {
            // 不回 ACK：消息留于 PEL，等待重新投递（At-least-once 保证不丢）
            log.error("[order-consumer] process failed, WILL REDELIVER, outboxEventId={}, recordId={}",
                    eventId, recordId, e);
        }
    }

    /** 从事件载荷解析订单号（payload JSON 的 orderNo 字段），尽力而为。 */
    private String resolveOrderNo(Object payload) {
        if (payload == null) {
            return "UNKNOWN";
        }
        String raw = String.valueOf(payload);
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode orderNo = node.get("orderNo");
            if (orderNo != null && orderNo.isTextual()) {
                return orderNo.asText();
            }
            JsonNode aggregateId = node.get("aggregateId");
            if (aggregateId != null && aggregateId.isTextual()) {
                return aggregateId.asText();
            }
        } catch (JsonProcessingException ignored) {
            // payload 非 JSON（异常数据）时仅用于日志，不影响已下单业务幂等回写
        }
        return raw.length() > 64 ? raw.substring(0, 64) : raw;
    }

    private void acknowledge(MapRecord<String, String, Object> message) {
        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            streamOps.acknowledge(RedisStreamConstants.STREAM_ORDER_EVENTS,
                    RedisStreamConstants.GROUP_ORDER_DISPATCH, message.getId());
        } catch (Exception e) {
            log.warn("[order-consumer] ACK failed, message will be redelivered, recordId={}, cause={}",
                    message.getId() != null ? message.getId().getValue() : "-", e.toString());
        }
    }
}
