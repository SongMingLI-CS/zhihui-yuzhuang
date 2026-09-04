package com.yuzhuang.infrastructure.redis;

/**
 * Redis Streams 事件总线常量（智汇于庄 · 特产订单域 Outbox 可靠投递）。
 *
 * <p>Redis 7 Streams 语义：
 * <ul>
 *   <li>同一 Stream 可由多个消费者组并行消费，组内消息仅在组内单次投递给一个消费者；</li>
 *   <li>配合消费侧 ACK，实现 At-least-once 投递（消费者需幂等）。</li>
 * </ul>
 */
public final class RedisStreamConstants {

    /** 订单事件 Stream 键（XADD / XREADGROUP 载体） */
    public static final String STREAM_ORDER_EVENTS = "stream:order:events";

    /** 订单事件消费者组（组内消息仅由一个消费者实例处理） */
    public static final String GROUP_ORDER_DISPATCH = "group_order_dispatch";

    /** 本后端实例消费者名（横向扩容多实例部署时需按实例唯一化） */
    public static final String CONSUMER_BACKEND = "consumer-backend-1";

    /** Stream 消息字段：Outbox 事件主键（用于幂等回写 / 日志追踪） */
    public static final String FIELD_EVENT_ID = "eventId";

    /** Stream 消息字段：事件 JSON 载荷 */
    public static final String FIELD_PAYLOAD = "payload";

    private RedisStreamConstants() {
    }
}
