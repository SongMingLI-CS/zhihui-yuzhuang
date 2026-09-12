package com.yuzhuang.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 运行环境与演示开关（唯一事实源）。
 *
 * <p>环境取值（{@code yuzhuang.app-env} / {@code APP_ENV}）：
 * <ul>
 *   <li>{@code production} / {@code prod}：生产。禁止演示账号、演示数据、默认密钥、Mock AI；</li>
 *   <li>{@code demo} / {@code dev} / {@code test}：仅本地/演示/测试，允许显式演示内容。</li>
 * </ul>
 *
 * <p>演示能力开关（{@code yuzhuang.demo.enabled} / {@code DEMO_MODE}）：必须显式打开，
 * 用于演示账号自举、可选的演示灌数。生产环境即便误开，也会被
 * {@link ProductionSafetyValidator} 在启动阶段拒绝。
 */
@Component
public class AppEnvironment {

    /** 生产环境别名集合。 */
    private static final Set<String> PRODUCTION_ALIASES = Set.of("production", "prod");

    /** 非生产（允许演示）环境别名集合。 */
    private static final Set<String> NON_PRODUCTION_ALIASES = Set.of("dev", "local", "demo", "test");

    private final String appEnv;
    private final boolean demoEnabled;

    public AppEnvironment(@Value("${yuzhuang.app-env:dev}") String appEnv,
                          @Value("${yuzhuang.demo.enabled:false}") boolean demoEnabled) {
        this.appEnv = normalize(appEnv);
        this.demoEnabled = demoEnabled;
    }

    /** 规整后的环境标识（小写）。 */
    public String getName() {
        return appEnv;
    }

    /** 是否生产环境。 */
    public boolean isProduction() {
        return PRODUCTION_ALIASES.contains(appEnv);
    }

    /** 演示开关是否显式打开。 */
    public boolean isDemoEnabled() {
        return demoEnabled;
    }

    /**
     * 是否允许加载演示账号 / 演示业务数据。
     * 生产环境永久为 {@code false}（即使 {@code DEMO_MODE=true} 误开，也由启动校验直接失败）。
     */
    public boolean isDemoDataAllowed() {
        return demoEnabled && !isProduction();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "dev";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        // 未知取值一律按非生产处理，但会由 ProductionSafetyValidator 记录警告。
        if (!PRODUCTION_ALIASES.contains(v) && !NON_PRODUCTION_ALIASES.contains(v)) {
            return v;
        }
        return v;
    }
}
