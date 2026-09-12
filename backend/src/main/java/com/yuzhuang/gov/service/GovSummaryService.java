package com.yuzhuang.gov.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.audit.service.AuditService;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;
import com.yuzhuang.dashboard.service.DashboardService;
import com.yuzhuang.gov.dto.GovSummaryResponse;
import com.yuzhuang.tenant.entity.Tenant;
import com.yuzhuang.tenant.mapper.TenantMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 政府治理聚合服务（阶段 D，只读）。
 *
 * <p>按 {@link GovScopeService} 解析出的授权范围汇总指定租户集合的经营指标；
 * 返回总量、分租户下钻、冻结口径说明与数据更新时间，且不含任何 PII。
 * 每次访问写审计日志（GOV_SUMMARY_VIEW）。
 */
@Service
public class GovSummaryService {

    /** 口径版本：变更统计定义时递增，便于端上标识。 */
    private static final String METRIC_VERSION = "2026.09-v1";

    private final GovScopeService govScopeService;
    private final DashboardService dashboardService;
    private final TenantMapper tenantMapper;
    private final AuditService auditService;

    public GovSummaryService(GovScopeService govScopeService, DashboardService dashboardService,
                             TenantMapper tenantMapper, AuditService auditService) {
        this.govScopeService = govScopeService;
        this.dashboardService = dashboardService;
        this.tenantMapper = tenantMapper;
        this.auditService = auditService;
    }

    /** 按授权范围聚合只读指标。 */
    public GovSummaryResponse summary(AuthPrincipal principal) {
        GovScopeService.ScopeResolution scope = govScopeService.resolve(principal);
        List<Tenant> tenants = resolveTenants(scope);

        long totalOrders = 0L;
        BigDecimal totalSales = BigDecimal.ZERO;
        long todayOrders = 0L;
        BigDecimal todaySales = BigDecimal.ZERO;
        long pendingPayOrders = 0L;
        long readyShipOrders = 0L;
        List<GovSummaryResponse.TenantBreakdown> breakdown = new ArrayList<>(tenants.size());

        for (Tenant tenant : tenants) {
            DashboardSummaryResponse row;
            try {
                row = dashboardService.summary(tenant.getTenantId());
            } catch (Exception ex) {
                // 单租户聚合异常不应中断整体大屏（该租户按 0 计入）
                continue;
            }
            totalOrders += row.getTotalOrders();
            totalSales = totalSales.add(row.getTotalSales());
            todayOrders += row.getTodayOrders();
            todaySales = todaySales.add(row.getTodaySales());
            pendingPayOrders += row.getPendingPayOrders();
            readyShipOrders += row.getReadyShipOrders();
            breakdown.add(GovSummaryResponse.TenantBreakdown.builder()
                    .tenantId(tenant.getTenantId())
                    .tenantName(tenant.getName())
                    .region(tenant.getRegion())
                    .totalOrders(row.getTotalOrders())
                    .totalSales(row.getTotalSales())
                    .todayOrders(row.getTodayOrders())
                    .build());
        }

        String description = scope.description() + "（实际汇总 " + tenants.size() + " 个租户）";
        auditService.record("GOV_SUMMARY_VIEW", "SCOPE", String.valueOf(tenants.size()),
                description, true);

        return GovSummaryResponse.builder()
                .snapshotAt(System.currentTimeMillis())
                .scope(GovSummaryResponse.ScopeMeta.builder()
                        .all(scope.all())
                        .tenantCount(tenants.size())
                        .description(description)
                        .build())
                .metrics(GovSummaryResponse.MetricDefinition.builder()
                        .salesAmount("交易额 = 非 CANCELLED 订单 total_amount 之和（含待支付锁定 GMV）")
                        .orderCount("订单量 = 授权范围内全部订单数（含已取消）")
                        .cancelledOrders("取消/超时关单单计入订单量，不计入交易额")
                        .version(METRIC_VERSION)
                        .build())
                .totals(GovSummaryResponse.Totals.builder()
                        .totalOrders(totalOrders)
                        .totalSales(totalSales)
                        .todayOrders(todayOrders)
                        .todaySales(todaySales)
                        .pendingPayOrders(pendingPayOrders)
                        .readyShipOrders(readyShipOrders)
                        .build())
                .breakdown(breakdown)
                .build();
    }

    /** 解析授权范围内的业务租户（剔除 PLATFORM 平台租户与 global 共享目录）。 */
    private List<Tenant> resolveTenants(GovScopeService.ScopeResolution scope) {
        LambdaQueryWrapper<Tenant> wrapper = new LambdaQueryWrapper<Tenant>()
                .ne(Tenant::getTenantType, "PLATFORM")
                .orderByAsc(Tenant::getId);
        if (!scope.all()) {
            Set<String> ids = new LinkedHashSet<>(scope.tenantIds());
            if (ids.isEmpty()) {
                return List.of();
            }
            wrapper.in(Tenant::getTenantId, ids);
        }
        return tenantMapper.selectList(wrapper);
    }
}
