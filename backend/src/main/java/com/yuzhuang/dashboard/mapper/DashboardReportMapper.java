package com.yuzhuang.dashboard.mapper;

import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 经营大盘聚合报表 Mapper（只读，按租户数据域）。
 *
 * <p>SQL 同时兼容 PostgreSQL 与 H2（PostgreSQL 模式）：仅使用 CASE/CAST/GROUP BY 等
 * 通用语法，便于 H2 test profile 全覆盖。金额字段以 NUMERIC 返回并做 COALESCE 防空。
 */
@Mapper
public interface DashboardReportMapper {

    /**
     * 租户合计：订单量 / 交易额（非 CANCELLED）/ 今日单量 / 今日交易额 / 待支付锁定 / 待出库。
     *
     * @param tenantId   租户（= JWT tenantId）
     * @param todayStart 今日零点
     */
    @Select("SELECT COUNT(*) AS total_orders, "
            + "COALESCE(SUM(CASE WHEN status <> 'CANCELLED' THEN total_amount ELSE 0 END), 0) AS total_sales, "
            + "SUM(CASE WHEN created_at >= #{todayStart} THEN 1 ELSE 0 END) AS today_orders, "
            + "COALESCE(SUM(CASE WHEN created_at >= #{todayStart} "
            + "         AND status <> 'CANCELLED' THEN total_amount ELSE 0 END), 0) AS today_sales, "
            + "SUM(CASE WHEN status = 'STOCK_CONFIRMED' AND paid_at IS NULL THEN 1 ELSE 0 END) AS pending_pay_orders, "
            + "SUM(CASE WHEN fulfillment_status = 'READY' THEN 1 ELSE 0 END) AS ready_ship_orders "
            + "FROM t_order WHERE tenant_id = #{tenantId}")
    DashboardSummaryResponse.TotalsRow loadTotals(@Param("tenantId") String tenantId,
                                                  @Param("todayStart") LocalDateTime todayStart);

    /**
     * 近 N 日按自然日聚合的订单量 / 交易额（非 CANCELLED），空日由服务层补零。
     *
     * @param tenantId 租户
     * @param start    起始时刻（含当日零点）
     */
    @Select("SELECT CAST(created_at AS DATE) AS date, "
            + "COUNT(*) AS order_count, "
            + "COALESCE(SUM(CASE WHEN status <> 'CANCELLED' THEN total_amount ELSE 0 END), 0) AS sales_amount "
            + "FROM t_order "
            + "WHERE tenant_id = #{tenantId} AND created_at >= #{start} "
            + "GROUP BY CAST(created_at AS DATE) "
            + "ORDER BY date ASC")
    List<DashboardSummaryResponse.TrendRow> selectTrend(@Param("tenantId") String tenantId,
                                                        @Param("start") LocalDateTime start);

    /**
     * 商品销量排行 TOP N（明细行 t_order_item JOIN t_order，排除已取消订单）。
     *
     * @param tenantId 租户
     * @param limit    返回条数上限
     */
    @Select("SELECT oi.sku_id AS sku_id, "
            + "COALESCE(p.spu_name, CONCAT('SKU#', oi.sku_id)) AS spu_name, "
            + "SUM(oi.quantity) AS quantity, "
            + "COALESCE(SUM(oi.subtotal), 0) AS sales_amount "
            + "FROM t_order_item oi "
            + "JOIN t_order o ON o.order_no = oi.order_no "
            + "LEFT JOIN t_product_sku p ON p.id = oi.sku_id "
            + "WHERE o.tenant_id = #{tenantId} AND o.status <> 'CANCELLED' "
            + "GROUP BY oi.sku_id, p.spu_name "
            + "ORDER BY sales_amount DESC, oi.sku_id ASC "
            + "LIMIT #{limit}")
    List<DashboardSummaryResponse.TopProductRow> selectTopProducts(@Param("tenantId") String tenantId,
                                                                   @Param("limit") int limit);
}
