package com.yuzhuang.dashboard;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.dashboard.dto.DashboardSummaryResponse;
import com.yuzhuang.dashboard.service.DashboardService;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.test.WebAuthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经营大盘聚合测试（test profile，H2）。
 * 覆盖：合计口径（非 CANCELLED 交易额 / 今日 / 待出库）、租户隔离、趋势补零、销量 TOP、HTTP 契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardSummaryTest extends WebAuthTestSupport {

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanTables() {
        orderItemMapper.delete(null);
        orderMapper.delete(null);
        productSkuMapper.delete(null);
    }

    @Test
    void summary_aggregatesRealOrdersWithTenantScope() {
        ProductSku oil = seedSku(1L, "SKU-D1", "于庄小磨香油");
        ProductSku flour = seedSku(2L, "SKU-D2", "于庄富硒小麦粉");

        // 今日：PROCESSING(已支付) 1 单 + READY 1 单 + CANCELLED 1 单
        Order todayPaid = seedOrder(TENANT_A, "ORD-D-1", OrderStatus.PROCESSING, FulfillmentStatus.PICKING,
                LocalDateTime.now(), true);
        seedItem(todayPaid.getOrderNo(), oil.getId(), 2, "100.00");
        Order todayReady = seedOrder(TENANT_A, "ORD-D-2", OrderStatus.PROCESSING, FulfillmentStatus.READY,
                LocalDateTime.now(), true);
        seedItem(todayReady.getOrderNo(), flour.getId(), 3, "50.00");
        seedOrder(TENANT_A, "ORD-D-5", OrderStatus.CANCELLED, FulfillmentStatus.PENDING,
                LocalDateTime.now(), false);
        // 历史（仍处趋势窗口）：昨日 / 6 天前
        seedOrder(TENANT_A, "ORD-D-3", OrderStatus.COMPLETED, FulfillmentStatus.SHIPPED,
                LocalDateTime.now().minusDays(1), true);
        seedOrder(TENANT_A, "ORD-D-4", OrderStatus.COMPLETED, FulfillmentStatus.SHIPPED,
                LocalDateTime.now().minusDays(6), true);
        // 他租户：不可见
        seedOrder(TENANT_B, "ORD-D-6", OrderStatus.PROCESSING, FulfillmentStatus.PICKING,
                LocalDateTime.now(), true);

        DashboardSummaryResponse resp = dashboardService.summary(TENANT_A);

        assertThat(resp.getTenantId()).isEqualTo(TENANT_A);
        assertThat(resp.getTotalOrders()).isEqualTo(5L);          // A 租户全量（含 CANCELLED）
        assertThat(resp.getTotalSales()).isEqualByComparingTo("600.00"); // 非 CANCELLED 4 单 × 150
        assertThat(resp.getTodayOrders()).isEqualTo(3L);          // 今日创建 3 单（含已取消）
        assertThat(resp.getTodaySales()).isEqualByComparingTo("300.00"); // 今日非 CANCELLED 2 单
        assertThat(resp.getReadyShipOrders()).isEqualTo(1L);
        assertThat(resp.getPendingPayOrders()).isZero();
        assertThat(resp.getTrend()).hasSize(7);
        assertThat(resp.getTrend().get(6).getDate()).isEqualTo(LocalDate.now());
        assertThat(resp.getTopProducts()).isNotEmpty();
        assertThat(resp.getTopProducts().get(0).getSkuId()).isEqualTo(oil.getId());
        assertThat(resp.getTopProducts().get(0).getSalesAmount()).isEqualByComparingTo("200.00");
    }
    @Test
    void summary_tenantIsolation_ignoresOtherTenant() {
        ProductSku other = seedSku(9L, "SKU-X1", "他租户商品");
        Order otherOrder = seedOrder(TENANT_B, "ORD-X-1", OrderStatus.PROCESSING, FulfillmentStatus.PICKING,
                LocalDateTime.now(), true);
        seedItem(otherOrder.getOrderNo(), other.getId(), 1, "10.00");

        DashboardSummaryResponse resp = dashboardService.summary(TENANT_A);
        assertThat(resp.getTotalOrders()).isZero();
        assertThat(resp.getTotalSales()).isEqualByComparingTo("0");
        assertThat(resp.getTrend()).hasSize(7);
        assertThat(resp.getTopProducts()).isEmpty();
    }

    @Test
    void summaryEndpoint_returns200WithAuthToken() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data.trend.length()").value(7));
    }

    private ProductSku seedSku(long id, String skuCode, String spuName) {
        ProductSku sku = ProductSku.builder()
                .id(id)
                .tenantId("global")
                .skuCode(skuCode)
                .spuName(spuName)
                .price(new BigDecimal("10.00"))
                .stock(1000)
                .version(0)
                .status("ON_SALE")
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }

    private Order seedOrder(String tenantId, String orderNo, OrderStatus status, FulfillmentStatus fulfillment,
                            LocalDateTime createdAt, boolean paid) {
        Order order = Order.builder()
                .tenantId(tenantId)
                .orderNo(orderNo)
                .idempotencyKey("idem_" + orderNo)
                .orderSource(OrderSource.H5_PRIVATE)
                .totalAmount(new BigDecimal("150.00"))
                .status(status)
                .fulfillmentStatus(fulfillment)
                .payChannel(paid ? "SANDBOX" : null)
                .payTradeNo(paid ? "trade_" + orderNo : null)
                .paidAt(paid ? createdAt : null)
                .recipientName("张三")
                .recipientPhone("13800000000")
                .detailedAddress("于庄村")
                .createdAt(createdAt)
                .build();
        orderMapper.insert(order);
        return order;
    }

    private void seedItem(String orderNo, Long skuId, int quantity, String unitPrice) {
        BigDecimal price = new BigDecimal(unitPrice);
        OrderItem item = OrderItem.builder()
                .orderNo(orderNo)
                .skuId(skuId)
                .quantity(quantity)
                .unitPrice(price)
                .subtotal(price.multiply(BigDecimal.valueOf(quantity)))
                .build();
        orderItemMapper.insert(item);
    }
}