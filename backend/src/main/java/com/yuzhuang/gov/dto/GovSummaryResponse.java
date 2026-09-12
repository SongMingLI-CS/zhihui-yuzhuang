package com.yuzhuang.gov.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 政府治理大屏聚合响应（GET /api/v1/gov/summary，阶段 D）。
 *
 * <p>只读聚合，按 {@code t_gov_scope} 授权范围汇总；包含统计口径与数据更新时间，
 * 不返回任何收货人/联系方式等 PII。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovSummaryResponse {

    /** 快照时刻（epoch 毫秒），即数据更新时间 */
    private long snapshotAt;

    /** 授权范围元数据 */
    private ScopeMeta scope;

    /** 统计口径说明（展示在端上，避免误读） */
    private MetricDefinition metrics;

    /** 汇总指标 */
    private Totals totals;

    /** 分租户下钻（村/合作社维度） */
    private List<TenantBreakdown> breakdown;

    /** 授权范围元数据。 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScopeMeta {
        /** 是否全量范围（显式 ALL 授权或平台管理员） */
        private boolean all;
        /** 授权租户数 */
        private int tenantCount;
        /** 范围描述（用于端上明示） */
        private String description;
    }

    /** 统计口径定义（避免“成交/取消/退款”口径歧义）。 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricDefinition {
        /** 交易额口径 */
        private String salesAmount;
        /** 订单量口径 */
        private String orderCount;
        /** 取消单口径 */
        private String cancelledOrders;
        /** 口径版本 */
        private String version;
    }

    /** 汇总指标（授权范围内合计）。 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private long totalOrders;
        private BigDecimal totalSales;
        private long todayOrders;
        private BigDecimal todaySales;
        private long pendingPayOrders;
        private long readyShipOrders;
    }

    /** 分租户下钻行。 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantBreakdown {
        private String tenantId;
        private String tenantName;
        private String region;
        private long totalOrders;
        private BigDecimal totalSales;
        private long todayOrders;
    }
}
