package com.yuzhuang.health;

/**
 * 健康检查服务抽象（便于测试时替换数据源实现）。
 */
public interface HealthService {

    /**
     * 执行一次健康探测。
     *
     * @return 健康信息，永不为 null
     */
    HealthInfo check();
}
