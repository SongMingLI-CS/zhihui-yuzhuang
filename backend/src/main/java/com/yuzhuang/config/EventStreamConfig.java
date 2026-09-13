package com.yuzhuang.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件流（SSE）线程池配置（阶段 D）。
 *
 * <p>用途：大屏/工作台的真实事件流长连接按固定间隔轮询 Outbox 并发 SSE；
 * 使用独立小线程池（默认 4 线程，守护线程）避免占用 Web 容器线程。
 * 连接超时由控制器主动完成（客户端会自动重连），不会无限占用线程。
 */
@Configuration
public class EventStreamConfig {

    /** 可并发维持的 SSE 连接数上限（按需要可用环境变量覆盖）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService eventStreamScheduler() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "event-stream-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(4, factory);
    }
}
