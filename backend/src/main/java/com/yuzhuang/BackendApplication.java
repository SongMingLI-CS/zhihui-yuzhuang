package com.yuzhuang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智汇于庄 backend · Spring Boot 启动入口。
 *
 * <p>{@code @EnableScheduling} 驱动 OutboxSweeper 定时扫描；{@code @EnableAsync}
 * 开放异步执行能力（后续业务通知等可异步化）。
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
