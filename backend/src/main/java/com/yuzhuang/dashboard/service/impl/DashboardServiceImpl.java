package com.yuzhuang.dashboard.service.impl;

import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;
import com.yuzhuang.dashboard.mapper.DashboardReportMapper;
import com.yuzhuang.dashboard.service.DashboardService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营大盘聚合服务实现（只读，单事务查询语义）。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final int TREND_DAYS = 7;
    private static final int TOP_PRODUCTS_LIMIT = 5;

    private final DashboardReportMapper dashboardReportMapper;

    public DashboardServiceImpl(DashboardReportMapper dashboardReportMapper) {
        this.dashboardReportMapper = dashboardReportMapper;
    }

    @Override
    public DashboardSummaryResponse summary(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户标识不能为空");
        }
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();

        DashboardSummaryResponse.TotalsRow totals = dashboardReportMapper.loadTotals(tenantId, dayStart);
        List<DashboardSummaryResponse.TrendRow> trend = fillTrend(tenantId, today, TREND_DAYS);
        List<DashboardSummaryResponse.TopProductRow> topProducts =
                dashboardReportMapper.selectTopProducts(tenantId, TOP_PRODUCTS_LIMIT);

        return DashboardSummaryResponse.builder()
                .tenantId(tenantId)
                .snapshotAt(System.currentTimeMillis())
                .totalOrders(nvl(totals.getTotalOrders()))
                .totalSales(nvlBig(totals.getTotalSales()))
                .todayOrders(nvl(totals.getTodayOrders()))
                .todaySales(nvlBig(totals.getTodaySales()))
                .pendingPayOrders(nvl(totals.getPendingPayOrders()))
                .readyShipOrders(nvl(totals.getReadyShipOrders()))
                .trend(trend)
                .topProducts(topProducts)
                .build();
    }

    /**
     * 先查库内聚合，再以自然日 map 补零，保证「近 N 日含今日」连续升序。
     */
    private List<DashboardSummaryResponse.TrendRow> fillTrend(String tenantId, LocalDate today, int days) {
        LocalDate startDate = today.minusDays(days - 1L);
        List<DashboardSummaryResponse.TrendRow> rows =
                dashboardReportMapper.selectTrend(tenantId, startDate.atStartOfDay());

        Map<LocalDate, DashboardSummaryResponse.TrendRow> byDate = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = startDate.plusDays(i);
            DashboardSummaryResponse.TrendRow empty = new DashboardSummaryResponse.TrendRow();
            empty.setDate(d);
            empty.setOrderCount(0L);
            empty.setSalesAmount(BigDecimal.ZERO);
            byDate.put(d, empty);
        }
        for (DashboardSummaryResponse.TrendRow row : rows) {
            if (row.getDate() == null) {
                continue;
            }
            DashboardSummaryResponse.TrendRow target = byDate.computeIfAbsent(row.getDate(), k -> {
                DashboardSummaryResponse.TrendRow r = new DashboardSummaryResponse.TrendRow();
                r.setOrderCount(0L);
                r.setSalesAmount(BigDecimal.ZERO);
                return r;
            });
            target.setOrderCount(nvl(row.getOrderCount()));
            target.setSalesAmount(nvlBig(row.getSalesAmount()));
        }
        return new ArrayList<>(byDate.values());
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private BigDecimal nvlBig(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
