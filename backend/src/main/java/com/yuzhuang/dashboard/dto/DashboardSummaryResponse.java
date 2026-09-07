package com.yuzhuang.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 经营大盘聚合响应（GET /api/v1/dashboard/summary，数据域=JWT tenantId）。
 *
 * <p>统计口径（docs/api-spec.yaml /dashboard/summary）：
 * <ul>
 *   <li>交易额口径：非 CANCELLED 订单 total_amount 合计（含待支付锁定的 GMV，扣除已取消）；</li>
 *   <li>trend：近 7 日（含今日）按自然日聚合，空日补零；</li>
 *   <li>topProducts：按明细行（t_order_item）汇总销量 TOP N（不含已取消订单）。</li>
 * </ul>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    /** 数据域租户（= JWT tenantId） */
    private String tenantId;

    /** 快照时刻（epoch 毫秒） */
    private long snapshotAt;

    /** 累计订单量 */
    private long totalOrders;

    /** 累计交易额（非 CANCELLED，元） */
    private BigDecimal totalSales;

    /** 今日订单量 */
    private long todayOrders;

    /** 今日交易额（元） */
    private BigDecimal todaySales;

    /** 待支付锁定订单量（STOCK_CONFIRMED 且未支付，超时将自动关单回补） */
    private long pendingPayOrders;

    /** 待出库订单量（履约 READY） */
    private long readyShipOrders;

    /** 近 7 日趋势（含今日，升序） */
    private List<TrendRow> trend;

    /** 销量 TOP N 商品 */
    private List<TopProductRow> topProducts;

    /** DB 合计行（MyBatis 直接映射）。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TotalsRow {
        private Long totalOrders;
        private BigDecimal totalSales;
        private Long todayOrders;
        private BigDecimal todaySales;
        private Long pendingPayOrders;
        private Long readyShipOrders;
    }

    /** 趋势点：自然日聚合（date 为 yyyy-MM-dd）。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TrendRow {
        private LocalDate date;
        private Long orderCount;
        private BigDecimal salesAmount;
    }

    /** 商品销量排行：明细行汇总（quantity 件 / salesAmount 元）。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class TopProductRow {
        private Long skuId;
        private String spuName;
        private Long quantity;
        private BigDecimal salesAmount;
    }
}
