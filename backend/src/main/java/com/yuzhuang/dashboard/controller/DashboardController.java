package com.yuzhuang.dashboard.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;
import com.yuzhuang.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营大盘聚合只读接口（严格对齐 docs/api-spec.yaml /dashboard/summary）。
 *
 * <p>需 JWT（任意已认证角色），数据域=令牌 tenantId 所属租户；
 * 用于 B 端 dashboard 替换 demo 快照数据（订单/营收趋势、销量 TOP 均来自真实库表）。
 */
@Slf4j
@Tag(name = "经营大盘", description = "真实订单聚合的运营大盘只读接口")
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "经营大盘汇总：合计 + 近 7 日趋势 + 商品销量 TOP 5")
    @GetMapping("/dashboard/summary")
    public ApiResponse<DashboardSummaryResponse> summary() {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[dashboard] summary tenantId={}", tenantId);
        return ApiResponse.success(dashboardService.summary(tenantId));
    }
}
