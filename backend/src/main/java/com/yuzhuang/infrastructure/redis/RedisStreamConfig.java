package com.yuzhuang.infrastructure.redis;

import com.yuzhuang.order.consumer.OrderEventStreamConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.util.ErrorHandler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis Streams 基础设施装配（订单域 Outbox 可靠投递）。
 *
 * <p>职责：
 * <ul>
 *   <li>启动时以 {@code XGROUP CREATE ... MKSTREAM} 幂等创建 Stream + 消费者组
 *       （容忍 {@code BUSYGROUP} 已存在）；</li>
 *   <li>装配 {@link StreamMessageListenerContainer}（异步拉取，自动注册
 *       {@link OrderEventStreamConsumer}），随应用生命周期 start/stop。</li>
 * </ul>
 *
 * <p>仅当 {@code yuzhuang.outbox.enabled=true} 时装配；测试 profile 关闭，
 * 保证既有 {@code @SpringBootTest} 不触碰真实 Redis。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "yuzhuang.outbox", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RedisStreamConfig {

    /**
     * Stream 监听容器 Bean：先确保 Stream/消费者组存在，再注册消费者并启动。
     *
     * <p>{@code destroyMethod = "stop"} 保证应用优雅停机时容器释放订阅。
     */
    @SuppressWarnings("unchecked")
    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, Object>> orderEventStreamContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate stringRedisTemplate,
            OrderEventStreamConsumer orderEventStreamConsumer) {

        ensureConsumerGroup(stringRedisTemplate);

        // 值序列化统一为 String：Stream hash 字段（eventId / payload）本就是字符串。
        // MapRecord<String,String,Object> 仅声明类型，运行时值仍为 String。
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> stringValueSerializer = (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;

        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "outbox-stream-consumer");
            t.setDaemon(true);
            return t;
        });

        // 说明：OptionsBuilder 的 keySerializer/hashKeySerializer/hashValueSerializer 各自
        // 重建泛型（重定型），javac 对长链的 target-type 推导不稳定，此处经 raw 中转 + 一次性
        // 转型为声明类型 MapRecord<String,String,Object>（运行时 hash 值本就是 String）。
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, Object>> options =
                (StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, Object>>)
                        (StreamMessageListenerContainer.StreamMessageListenerContainerOptions) StreamMessageListenerContainer
                                .StreamMessageListenerContainerOptions
                                .builder()
                                .keySerializer(RedisSerializer.string())
                                .hashKeySerializer(RedisSerializer.string())
                                .hashValueSerializer(stringValueSerializer)
                                .pollTimeout(Duration.ofMillis(200))
                                .batchSize(10)
                                .executor(consumerExecutor)
                                .errorHandler(streamErrorHandler())
                                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, Object>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
                Consumer.from(RedisStreamConstants.GROUP_ORDER_DISPATCH, RedisStreamConstants.CONSUMER_BACKEND),
                StreamOffset.create(RedisStreamConstants.STREAM_ORDER_EVENTS, ReadOffset.lastConsumed()),
                orderEventStreamConsumer);

        container.start();
        log.info("[redis-stream] listener container started: stream={}, group={}, consumer={}",
                RedisStreamConstants.STREAM_ORDER_EVENTS,
                RedisStreamConstants.GROUP_ORDER_DISPATCH,
                RedisStreamConstants.CONSUMER_BACKEND);
        return container;
    }

    /** 消费者轮询循环兜底错误处理（不影响消息本身，防止未知异常终止线程）。 */
    private ErrorHandler streamErrorHandler() {
        return t -> log.error("[redis-stream] consumer loop error", t);
    }

    /**
     * 幂等创建 Stream 与消费者组：{@code XGROUP CREATE stream group $ MKSTREAM}。
     *
     * <p>组已存在（BUSYGROUP）视为正常；连接类异常在启动期仅告警，
     * 后续由消费链路自行容错恢复，不阻断应用启动。
     */
    private void ensureConsumerGroup(StringRedisTemplate stringRedisTemplate) {
        try {
            String result = stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            RedisSerializer.string().serialize(RedisStreamConstants.STREAM_ORDER_EVENTS),
                            RedisStreamConstants.GROUP_ORDER_DISPATCH,
                            ReadOffset.latest(),
                            true));
            log.info("[redis-stream] consumer group ensured: stream={}, group={}, result={}",
                    RedisStreamConstants.STREAM_ORDER_EVENTS, RedisStreamConstants.GROUP_ORDER_DISPATCH, result);
        } catch (Exception e) {
            log.warn("[redis-stream] xgroup create skipped (group already exists or Redis temporarily unavailable): {}",
                    e.getMessage());
        }
    }
}
