package com.yuzhuang.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus Mapper 扫描配置。
 *
 * <p>显式声明各领域 Mapper 包路径，确保 @Mapper 接口在 Spring 容器启动时
 * 注册为代理 Bean（order / inventory / outbox 三个领域模块）。
 */
@Configuration
@MapperScan({
        "com.yuzhuang.order.mapper",
        "com.yuzhuang.inventory.mapper",
        "com.yuzhuang.outbox.mapper"
})
public class MybatisPlusConfig {
}
