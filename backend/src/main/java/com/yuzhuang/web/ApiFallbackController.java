package com.yuzhuang.web;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.enums.ResultCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/**} 兜底控制器。
 *
 * <p>目的（阶段 B）：让<b>所有</b> {@code /api/v1/**} 路径都能解析到 {@code HandlerMethod}，
 * 从而由 {@link com.yuzhuang.web.security.AuthGuardInterceptor} 统一执行
 * “未显式声明即要求认证”的 deny-by-default 策略；随后本控制器返回 404 + A1004。
 *
 * <p>更具体的控制器映射优先级高于本兜底，不会被遮蔽。
 */
@Hidden
@RestController
public class ApiFallbackController {

    @RequestMapping("/api/v1/**")
    public ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ResultCode.NOT_FOUND.getCode(), "接口不存在或资源未找到"));
    }
}
