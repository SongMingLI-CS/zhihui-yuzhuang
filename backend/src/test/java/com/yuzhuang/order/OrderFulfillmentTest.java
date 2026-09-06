package com.yuzhuang.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单履约出库（POST /orders/{orderNo}/ship）+ 履约状态过滤测试（test profile，H2 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderFulfillmentTest {

    private static final String TENANT_A = "tenant_yuzhuang_001";
    private static final String TENANT_B = "tenant_yuzhuang_002";

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
    void ship_readyOrder_returns200AndPersistsShipped() throws Exception {
        seedOrder(TENANT_A, "ORD-SHP-1", "STOCK_CONFIRMED", "READY", "H5_PRIVATE");

        mockMvc.perform(post("/api/v1/orders/ORD-SHP-1/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-SHP-1"))
                .andExpect(jsonPath("$.data.status").value("STOCK_CONFIRMED"))
                .andExpect(jsonPath("$.data.fulfillmentStatus").value("SHIPPED"));

        Order persisted = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, TENANT_A)
                .eq(Order::getOrderNo, "ORD-SHP-1"));
        assertThat(persisted).isNotNull();
        assertThat(persisted.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.SHIPPED);
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.STOCK_CONFIRMED);
    }

    @Test
    void ship_notReadyOrder_returns409B2003() throws Exception {
        seedOrder(TENANT_A, "ORD-SHP-2", "PROCESSING", "PICKING", "DOUYIN");

        mockMvc.perform(post("/api/v1/orders/ORD-SHP-2/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2003"));

        Order persisted = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, TENANT_A)
                .eq(Order::getOrderNo, "ORD-SHP-2"));
        assertThat(persisted.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.PICKING);
    }

    @Test
    void ship_missingOrder_returns404A1004() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-NO-SUCH/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));
    }

    @Test
    void ship_crossTenant_returns404A1004() throws Exception {
        seedOrder(TENANT_A, "ORD-SHP-3", "STOCK_CONFIRMED", "READY", "H5_PRIVATE");

        mockMvc.perform(post("/api/v1/orders/ORD-SHP-3/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));

        Order untouched = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, TENANT_A)
                .eq(Order::getOrderNo, "ORD-SHP-3"));
        assertThat(untouched.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.READY);
    }

    @Test
    void ship_doubleShip_secondCallReturns409B2003() throws Exception {
        seedOrder(TENANT_A, "ORD-SHP-4", "STOCK_CONFIRMED", "READY", "H5_PRIVATE");

        mockMvc.perform(post("/api/v1/orders/ORD-SHP-4/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders/ORD-SHP-4/ship")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2003"));
    }

    @Test
    void list_filterByFulfillmentStatus_returnsOnlyReady() throws Exception {
        seedOrder(TENANT_A, "ORD-FILT-1", "STOCK_CONFIRMED", "READY", "H5_PRIVATE");
        seedOrder(TENANT_A, "ORD-FILT-2", "PROCESSING", "SHIPPED", "DOUYIN");
        seedOrder(TENANT_A, "ORD-FILT-3", "PROCESSING", "PICKING", "KUAISHOU");

        mockMvc.perform(get("/api/v1/orders")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A)
                        .param("fulfillmentStatus", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("ORD-FILT-1"))
                .andExpect(jsonPath("$.data.items[0].fulfillmentStatus").value("READY"));
    }

    @Test
    void list_invalidFulfillmentStatus_returns400A1001() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .header(HeaderNames.X_TENANT_ID, TENANT_A)
                        .param("fulfillmentStatus", "NOT_A_FULFILLMENT_STATE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }

    private Order seedOrder(String tenantId, String orderNo, String status, String fulfillmentStatus,
                            String source) {
        Order order = Order.builder()
                .tenantId(tenantId)
                .orderNo(orderNo)
                .idempotencyKey("idem_" + orderNo)
                .orderSource(OrderSource.valueOf(source))
                .totalAmount(new BigDecimal("90.00"))
                .status(OrderStatus.valueOf(status))
                .fulfillmentStatus(FulfillmentStatus.valueOf(fulfillmentStatus))
                .recipientName("张三")
                .recipientPhone("13800000000")
                .detailedAddress("河南省周口市鹿邑县试量镇于庄村")
                .remark("测试订单")
                .createdAt(LocalDateTime.now())
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