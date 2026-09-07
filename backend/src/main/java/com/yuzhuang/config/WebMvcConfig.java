package com.yuzhuang.config;

import com.yuzhuang.web.security.AuthGuardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：为业务 API 前缀 {@code /api/v1/**} 注册认证/授权强制拦截器。
 *
 * <p>公开端点（登录/健康探针/商品读/下单）由 {@link AuthGuardInterceptor} 内部白名单放行；
 * 拦截器未覆盖的路径（swagger、探针、静态资源）维持原访问策略。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthGuardInterceptor authGuardInterceptor;

    public WebMvcConfig(AuthGuardInterceptor authGuardInterceptor) {
        this.authGuardInterceptor = authGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authGuardInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}
