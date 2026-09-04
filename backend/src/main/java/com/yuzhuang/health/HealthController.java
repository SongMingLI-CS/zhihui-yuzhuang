package com.yuzhuang.health;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.enums.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活/就绪探针：{@code GET /api/v1/healthz}。
 * 内部执行 {@code SELECT 1} 校验数据库健康；健康返回标准成功
 * {@link ApiResponse}，异常返回 HTTP 503 + C5002。
 */
@Slf4j
@Tag(name = "健康检查", description = "存活与数据库就绪探针")
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "健康检查（含 DB SELECT 1 探测）")
    @GetMapping("/healthz")
    public ResponseEntity<ApiResponse<HealthInfo>> healthz() {
        HealthInfo info = healthService.check();
        if (info.healthy()) {
            return ResponseEntity.ok(ApiResponse.success(info));
        }
        log.warn("[health] unhealthy: status={}, db={}", info.getStatus(), info.getDb());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.<HealthInfo>fail(ResultCode.DEPENDENT_SERVICE_ERROR.getCode(), "database unavailable"));
    }
}
