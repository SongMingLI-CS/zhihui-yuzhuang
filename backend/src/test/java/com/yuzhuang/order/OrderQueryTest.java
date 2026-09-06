package com.yuzhuang.order;

import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.order.dto.OrderDetailResponse;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单查询聚合接口 · 分页/过滤/租户隔离/详情测试（test profile，H2 PostgreSQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderQueryTest {

    private static final String GLOBAL = "global";
    private static final String TENANT_A = "tenant_yuzhuang_001";
    private static final String TENANT_B = "tenant_yuzhuang_002";

    @Autowired
    private OrderQueryService orderQueryService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanTables() {
        TenantContext.clear();
        orderItemMapper.delete(null);
        orderMapper.delete(null);
    }

    @Test
    void listOrders_pagination_returnsPageAndTotal() {
        for (int i = 1; i <= 25; i++) {
            seedOrder(TENANT_A, "ORD-A-" + String.format("%03d", i), "STOCK_CONFIRMED", "H5_PRIVATE",
                    LocalDateTime.now().minusMinutes(i));
        }
        PageResult<OrderSummaryResponse> result = orderQueryService.listOrders(TENANT_A, null, null, 1, 10);
        assertThat(result.getTotal()).isEqualTo(25L);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getItems()).hasSize(10);
        assertThat(result.getItems().get(0).getOrderNo()).isEqualTo("ORD-A-001");
    }

    @Test
    void listOrders_filterByStatus() {
        seedOrder(TENANT_A, "ORD-S-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedOrder(TENANT_A, "ORD-S-2", "PROCESSING", "H5_PRIVATE", LocalDateTime.now());
        PageResult<OrderSummaryResponse> result =
                orderQueryService.listOrders(TENANT_A, OrderStatus.STOCK_CONFIRMED, null, 1, 10);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems().get(0).getOrderNo()).isEqualTo("ORD-S-1");
    }

    @Test
    void listOrders_filterBySource() {
        seedOrder(TENANT_A, "ORD-C-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedOrder(TENANT_A, "ORD-C-2", "STOCK_CONFIRMED", "DOUYIN", LocalDateTime.now());
        PageResult<OrderSummaryResponse> result =
                orderQueryService.listOrders(TENANT_A, null, OrderSource.H5_PRIVATE, 1, 10);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems().get(0).getOrderNo()).isEqualTo("ORD-C-1");
    }

    @Test
    void listOrders_tenantIsolation() {
        seedOrder(TENANT_A, "ORD-TA-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedOrder(TENANT_A, "ORD-TA-2", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedOrder(TENANT_B, "ORD-TB-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());

        PageResult<OrderSummaryResponse> resultA = orderQueryService.listOrders(TENANT_A, null, null, 1, 10);
        assertThat(resultA.getTotal()).isEqualTo(2L);
        assertThat(resultA.getItems()).allMatch(o -> o.getOrderNo().startsWith("ORD-TA-"));
    }

    @Test
    void listOrders_blankTenant_defaultsToGlobal() {
        seedOrder(GLOBAL, "ORD-G-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        PageResult<OrderSummaryResponse> result = orderQueryService.listOrders("  ", null, null, 1, 10);
        assertThat(result.getTotal()).isEqualTo(1L);
    }

    @Test
    void getOrderDetail_returnsItems() {
        seedOrder(TENANT_A, "ORD-D-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedItem("ORD-D-1", 1001L, 2, "45.00");
        seedItem("ORD-D-1", 1002L, 1, "30.00");

        OrderDetailResponse detail = orderQueryService.getOrderDetail(TENANT_A, "ORD-D-1");
        assertThat(detail.getOrderNo()).isEqualTo("ORD-D-1");
        assertThat(detail.getItems()).hasSize(2);
        assertThat(detail.getItems().get(0).getSkuId()).isEqualTo(1001L);
    }

    @Test
    void getOrderDetail_notFound_throwsA1004() {
        assertThatThrownBy(() -> orderQueryService.getOrderDetail(TENANT_A, "ORD-NOT-EXIST"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void getOrderDetail_crossTenant_throwsA1004() {
        seedOrder(TENANT_B, "ORD-X-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        assertThatThrownBy(() -> orderQueryService.getOrderDetail(TENANT_A, "ORD-X-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void listEndpoint_returnsPageResult() throws Exception {
        seedOrder(TENANT_A, "ORD-H-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedOrder(TENANT_A, "ORD-H-2", "PROCESSING", "H5_PRIVATE", LocalDateTime.now());

        mockMvc.perform(get("/api/v1/orders")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A)
                        .param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void detailEndpoint_returnsDetail() throws Exception {
        seedOrder(TENANT_A, "ORD-HD-1", "STOCK_CONFIRMED", "H5_PRIVATE", LocalDateTime.now());
        seedItem("ORD-HD-1", 1001L, 2, "45.00");

        mockMvc.perform(get("/api/v1/orders/ORD-HD-1").header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-HD-1"))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void detailEndpoint_notFound_returns404A1004() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-NONE").header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void listEndpoint_invalidStatus_returns400A1001() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }

    private Order seedOrder(String tenantId, String orderNo, String status, String source, LocalDateTime createdAt) {
        Order order = Order.builder()
                .tenantId(tenantId)
                .orderNo(orderNo)
                .idempotencyKey("idem_" + orderNo)
                .orderSource(OrderSource.valueOf(source))
                .totalAmount(new BigDecimal("90.00"))
                .status(OrderStatus.valueOf(status))
                .recipientName("张三")
                .recipientPhone("13800000000")
                .detailedAddress("河南省周口市鹿邑县试量镇于庄村")
                .remark("测试订单")
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


