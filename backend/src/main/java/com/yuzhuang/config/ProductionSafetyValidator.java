package com.yuzhuang.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 生产安全启动校验（fail-fast）。
 *
 * <p>生产环境（{@code APP_ENV=production}）下，本校验在 Bean 初始化阶段直接抛异常终止启动，
 * 避免以下“悄悄用 demo 默认值上线”的事故：
 * <ol>
 *   <li>缺少高熵 {@code AUTH_JWT_SECRET}，或仍在使用代码内开发默认密钥；</li>
 *   <li>误将 {@code DEMO_MODE=true} / demo profile 带上生产；</li>
 *   <li>启用启动期演示账号自举或演示 SQL 灌数；</li>
 *   <li>仍然使用开发默认数据库口令。</li>
 * </ol>
 *
 * <p>非生产环境只记录告警，不阻断启动，保证本地/dev/demo/test 体验不变。
 */
@Slf4j
@Component
public class ProductionSafetyValidator {

    /** 生产必须显式开启的 profile 白名单（禁止 demo/dev/test 生效）。 */
    private static final Set<String> FORBIDDEN_PROFILES_IN_PRODUCTION = Set.of("demo", "dev", "test");

    /** 开发默认数据库口令（application.yml / compose 默认值）。 */
    private static final String DEV_DB_PASSWORD = "rural_password";

    private final AppEnvironment appEnvironment;
    private final String jwtSecret;
    private final String datasourcePassword;
    private final String sqlInitMode;
    private final String[] activeProfiles;

    public ProductionSafetyValidator(AppEnvironment appEnvironment,
                                     @Value("${yuzhuang.auth.secret:}") String jwtSecret,
                                     @Value("${spring.datasource.password:}") String datasourcePassword,
                                     @Value("${spring.sql.init.mode:never}") String sqlInitMode,
                                     Environment environment) {
        this.appEnvironment = appEnvironment;
        this.jwtSecret = jwtSecret;
        this.datasourcePassword = datasourcePassword;
        this.sqlInitMode = sqlInitMode;
        this.activeProfiles = environment.getActiveProfiles();
    }

    @PostConstruct
    public void validate() {
        if (!appEnvironment.isProduction()) {
            if (appEnvironment.isDemoEnabled()) {
                log.warn("[security] 演示开关已打开（app-env={}）。演示账号/数据仅用于非生产环境。",
                        appEnvironment.getName());
            }
            return;
        }

        // ---- 生产硬性约束 ----
        if (appEnvironment.isDemoEnabled()) {
            throw new IllegalStateException(
                    "生产环境禁止 DEMO_MODE/yuzhuang.demo.enabled=true：演示账号与演示数据不得进入生产。");
        }

        for (String profile : activeProfiles) {
            if (FORBIDDEN_PROFILES_IN_PRODUCTION.contains(profile.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "生产环境禁止激活 '" + profile + "' profile（会启用演示账号/测试数据）。"
                                + "activeProfiles=" + Arrays.toString(activeProfiles));
            }
        }

        validateJwtSecret();

        if ("always".equalsIgnoreCase(sqlInitMode)) {
            throw new IllegalStateException(
                    "生产环境禁止 spring.sql.init.mode=always（会自动灌入演示种子数据）。请改为 never 并使用受控迁移。");
        }

        if (DEV_DB_PASSWORD.equals(datasourcePassword)) {
            throw new IllegalStateException(
                    "生产环境禁止使用开发默认数据库口令，请通过 secret/环境变量注入高熵 POSTGRES_PASSWORD。");
        }

        log.info("[security] 生产安全校验通过：demo 关闭、JWT 密钥高熵、无演示灌数。");
    }

    private void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("生产环境缺少高熵 AUTH_JWT_SECRET，拒绝启动。");
        }
        if (SecurityDefaults.DEV_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "生产环境检测到代码内开发默认 JWT 密钥，拒绝启动。请注入高熵 AUTH_JWT_SECRET。");
        }
        if (jwtSecret.length() < SecurityDefaults.MIN_PRODUCTION_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "生产环境 AUTH_JWT_SECRET 长度不足（至少 "
                            + SecurityDefaults.MIN_PRODUCTION_SECRET_LENGTH + " 字符），拒绝启动。");
        }
    }
}
