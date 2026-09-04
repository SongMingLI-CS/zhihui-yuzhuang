package com.yuzhuang.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 JDBC 的健康检查实现：执行 {@code SELECT 1} 验证数据库连通。
 */
@Slf4j
@Service
public class DbHealthService implements HealthService {

    private final JdbcTemplate jdbcTemplate;

    public DbHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public HealthInfo check() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) {
                return new HealthInfo(HealthInfo.STATUS_UP, HealthInfo.STATUS_UP);
            }
            return new HealthInfo(HealthInfo.STATUS_DOWN, HealthInfo.STATUS_DOWN);
        } catch (DataAccessException e) {
            log.error("[health] database health check failed, requestId present in MDC", e);
            return new HealthInfo(HealthInfo.STATUS_DOWN, HealthInfo.STATUS_DOWN);
        }
    }
}
