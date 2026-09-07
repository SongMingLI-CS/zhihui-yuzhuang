package com.yuzhuang.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.order.dto.PaymentSandboxPayRequest;
import com.yuzhuang.order.dto.PaymentSandboxResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderPaymentClosureService;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import com.yuzhuang.test.WebAuthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付（沙箱）与超时关单闭环测试（test profile，H2）。
 * 覆盖：支付成功流转与幂等、重复支付拒绝、关单+库存回补幂等与并发单胜、端点契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPaymentClosureTest extends WebAuthTestSupport {

    private static final String TENANT_A = "tenant_yuzhuang_001";
    private static final String CHANNEL = "SANDBOX";

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private OrderPaymentClosureService paymentClosureService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        orderItemMapper.delete(null);
        orderMapper.delete(null);
        productSkuMapper.delete(null);
    }

    @Test
    void sandboxPay_success_marksProcessingAndRecordsPayment() {
        ProductSku sku = seedSku("SKU-PAY-1", 100);
        seedOrder(TENANT_A, "ORD-PAY-1", LocalDateTime.now().minusMinutes(5));
        seedItem("ORD-PAY-1", sku.getId(), 2);

        PaymentSandboxResponse resp = paymentClosureService.sandboxPay(
                TENANT_A, "ORD-PAY-1", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9001"));

        assertThat(resp.getStatus()).isEqualTo(OrderStatus.PROCESSING.name());
        assertThat(resp.getTradeNo()).isEqualTo("pay_trade_9001");
        Order persisted = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, "ORD-PAY-1"));
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(persisted.getPaidAt()).isNotNull();
        assertThat(persisted.getPayTradeNo()).isEqualTo("pay_trade_9001");
        assertThat(productSkuMapper.selectById(sku.getId()).getStock()).isEqualTo(100);
        assertThat(outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getAggregateId, "ORD-PAY-1")
                .eq(OutboxEvent::getEventType, "ORDER_PAID"))).isEqualTo(1);
    }
    @Test
    void sandboxPay_sameTradeIdempotentReplay() {
        ProductSku sku = seedSku("SKU-PAY-2", 10);
        seedOrder(TENANT_A, "ORD-PAY-2", LocalDateTime.now().minusMinutes(5));
        seedItem("ORD-PAY-2", sku.getId(), 1);

        PaymentSandboxResponse first = paymentClosureService.sandboxPay(
                TENANT_A, "ORD-PAY-2", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9002"));
        PaymentSandboxResponse replay = paymentClosureService.sandboxPay(
                TENANT_A, "ORD-PAY-2", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9002"));

        assertThat(replay.getStatus()).isEqualTo(OrderStatus.PROCESSING.name());
        assertThat(replay.getPaidAt()).isEqualTo(first.getPaidAt());
    }

    @Test
    void sandboxPay_differentTrade_throwsB2003() {
        ProductSku sku = seedSku("SKU-PAY-3", 10);
        seedOrder(TENANT_A, "ORD-PAY-3", LocalDateTime.now().minusMinutes(5));
        seedItem("ORD-PAY-3", sku.getId(), 1);
        paymentClosureService.sandboxPay(TENANT_A, "ORD-PAY-3", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9003"));

        assertThatThrownBy(() -> paymentClosureService.sandboxPay(
                TENANT_A, "ORD-PAY-3", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_OTHER")))
                .hasMessageContaining("重复支付");
    }

    @Test
    void sandboxPay_closedOrder_throwsA1004() {
        ProductSku sku = seedSku("SKU-PAY-4", 10);
        seedOrder(TENANT_A, "ORD-PAY-4", LocalDateTime.now().minusMinutes(61));
        seedItem("ORD-PAY-4", sku.getId(), 1);
        paymentClosureService.closeExpiredOrders(10);

        assertThatThrownBy(() -> paymentClosureService.sandboxPay(
                TENANT_A, "ORD-PAY-4", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9004")))
                .hasMessageContaining("已关闭");
    }

    @Test
    void closeExpired_releasesStock_onlyForExpiredUnpaid() {
        ProductSku sku = seedSku("SKU-PAY-5", 50);
        seedOrder(TENANT_A, "ORD-CL-1", LocalDateTime.now().minusMinutes(61));
        seedItem("ORD-CL-1", sku.getId(), 2);
        seedOrder(TENANT_A, "ORD-CL-2", LocalDateTime.now().minusMinutes(10));
        seedItem("ORD-CL-2", sku.getId(), 3);

        int closed = paymentClosureService.closeExpiredOrders(10);

        assertThat(closed).isEqualTo(1);
        Order closedOrder = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, "ORD-CL-1"));
        assertThat(closedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(closedOrder.getCloseReason()).isEqualTo("PAY_TIMEOUT");
        assertThat(closedOrder.getCancelledAt()).isNotNull();
        Order fresh = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, "ORD-CL-2"));
        assertThat(fresh.getStatus()).isEqualTo(OrderStatus.STOCK_CONFIRMED);
        assertThat(productSkuMapper.selectById(sku.getId()).getStock()).isEqualTo(52);
        assertThat(outboxEventMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getAggregateId, "ORD-CL-1")
                .eq(OutboxEvent::getEventType, "ORDER_CANCELLED"))).isEqualTo(1);
    }

    @Test
    void closeExpired_paidOrProcessingOrdersAreSkipped() {
        ProductSku sku = seedSku("SKU-PAY-6", 30);
        seedOrder(TENANT_A, "ORD-CL-P1", LocalDateTime.now().minusMinutes(61));
        seedItem("ORD-CL-P1", sku.getId(), 1);
        paymentClosureService.sandboxPay(TENANT_A, "ORD-CL-P1", new PaymentSandboxPayRequest(CHANNEL, "pay_trade_9006"));

        int closed = paymentClosureService.closeExpiredOrders(10);

        assertThat(closed).isZero();
        assertThat(productSkuMapper.selectById(sku.getId()).getStock()).isEqualTo(30);
    }

    @Test
    void closeExpired_concurrentExactlyOneClosesAndReleasesStock() throws Exception {
        ProductSku sku = seedSku("SKU-PAY-7", 30);
        seedOrder(TENANT_A, "ORD-CONC", LocalDateTime.now().minusMinutes(61));
        seedItem("ORD-CONC", sku.getId(), 4);

        int workers = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return paymentClosureService.closeExpiredOrders(10);
                }));
            }
            start.countDown();
            int totalClosed = 0;
            for (Future<Integer> f : futures) {
                totalClosed += f.get(20, TimeUnit.SECONDS);
            }
            assertThat(totalClosed).as("并发关单只有一次成功").isEqualTo(1);
            assertThat(productSkuMapper.selectById(sku.getId()).getStock())
                    .as("库存只回补一次").isEqualTo(34);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sandboxPayEndpoint_returns200WithCoopToken() throws Exception {
        ProductSku sku = seedSku("SKU-PAY-8", 20);
        seedOrder(TENANT_A, "ORD-EP-1", LocalDateTime.now().minusMinutes(5));
        seedItem("ORD-EP-1", sku.getId(), 1);
        String body = objectMapper.writeValueAsString(Map.of("channel", CHANNEL, "tradeNo", "pay_trade_9008"));

        mockMvc.perform(post("/api/v1/orders/ORD-EP-1/pay/sandbox")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-EP-1"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }
    private ProductSku seedSku(String skuCode, int stock) {
        ProductSku sku = ProductSku.builder()
                .tenantId("global")
                .skuCode(skuCode)
                .spuName("于庄测试商品-" + skuCode)
                .price(new BigDecimal("10.00"))
                .stock(stock)
                .version(0)
                .status("ON_SALE")
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }

    private Order seedOrder(String tenantId, String orderNo, LocalDateTime createdAt) {
        Order order = Order.builder()
                .tenantId(tenantId)
                .orderNo(orderNo)
                .idempotencyKey("idem_" + orderNo)
                .orderSource(OrderSource.H5_PRIVATE)
                .totalAmount(new BigDecimal("90.00"))
                .status(OrderStatus.STOCK_CONFIRMED)
                .fulfillmentStatus(FulfillmentStatus.PENDING)
                .recipientName("张三")
                .recipientPhone("13800000000")
                .detailedAddress("河南省周口市鹿邑县试量镇于庄村")
                .remark("测试订单")
                .createdAt(createdAt)
                .build();
        orderMapper.insert(order);
        return order;
    }

    private void seedItem(String orderNo, Long skuId, int quantity) {
        BigDecimal price = new BigDecimal("10.00");
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