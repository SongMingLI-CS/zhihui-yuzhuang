package com.yuzhuang.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产安全启动校验测试（阶段 A 验收项 1/2）：验证生产配置缺失/弱化时 fail-fast。
 *
 * <p>纯单元测试（不起 Spring 上下文），不依赖数据库/Redis。
 */
class ProductionSafetyValidatorTest {

    private static final String STRONG_SECRET = "a7f3c9d1e5b2048f6a1c3e5d7b9f0a2c4e6d8f10b3a5c7e9f1d3b5a7c9e1f3d50";

    private ProductionSafetyValidator validator(String appEnv, boolean demo, String secret,
                                                String dbPassword, String sqlInitMode,
                                                String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ProductionSafetyValidator(
                new AppEnvironment(appEnv, demo), secret, dbPassword, sqlInitMode, environment);
    }

    @Test
    void production_rejectsDemoMode() {
        assertThatThrownBy(() -> validator("production", true, STRONG_SECRET, "Str0ngPass!23", "never")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_MODE");
    }

    @Test
    void production_rejectsDefaultJwtSecret() {
        assertThatThrownBy(() -> validator("production", false,
                SecurityDefaults.DEV_JWT_SECRET, "Str0ngPass!23", "never").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("开发默认 JWT 密钥");
    }

    @Test
    void production_rejectsShortJwtSecret() {
        assertThatThrownBy(() -> validator("production", false, "short-secret", "Str0ngPass!23", "never")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    @Test
    void production_rejectsBlankJwtSecret() {
        assertThatThrownBy(() -> validator("production", false, "", "Str0ngPass!23", "never").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少高熵 AUTH_JWT_SECRET");
    }

    @Test
    void production_rejectsDemoProfile() {
        assertThatThrownBy(() -> validator("production", false, STRONG_SECRET, "Str0ngPass!23",
                "never", "demo").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    void production_rejectsSqlInitAlways() {
        assertThatThrownBy(() -> validator("production", false, STRONG_SECRET, "Str0ngPass!23", "always")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.sql.init.mode");
    }

    @Test
    void production_rejectsDevDbPassword() {
        assertThatThrownBy(() -> validator("production", false, STRONG_SECRET, "rural_password", "never")
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("开发默认数据库口令");
    }

    @Test
    void production_acceptsHardenedConfig() {
        assertThatCode(() -> validator("production", false, STRONG_SECRET, "Str0ngPass!23", "never")
                .validate())
                .doesNotThrowAnyException();
    }

    @Test
    void nonProduction_allowsDemoMode() {
        assertThatCode(() -> validator("demo", true, SecurityDefaults.DEV_JWT_SECRET,
                "rural_password", "always").validate())
                .doesNotThrowAnyException();
    }
}
