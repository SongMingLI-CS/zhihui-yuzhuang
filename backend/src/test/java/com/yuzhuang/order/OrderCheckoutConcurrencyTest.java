package com.yuzhuang.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.order.dto.OrderCheckoutRequest;
import com.yuzhuang.order.dto.OrderCheckoutResponse;
import com.yuzhuang.order.dto.OrderItemRequest;
import com.yuzhuang.order.dto.ReceiverAddress;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderService;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单交易闭环 · 高并发防超卖 + 幂等集成测试（test profile，H2 PostgreSQL 兼容模式）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>30 线程并发抢购 10 件库存：恰好 10 单成功（STOCK_CONFIRMED）、
 *       20 单 INVENTORY_STOCK_OUT(409)，最终库存严格为 0、Outbox 恰 10 条，绝不超卖；</li>
 *   <li>同一 idempotencyKey 顺序重复提交：回放同一订单，不重复扣库存/发事件；</li>
 *   <li>同一 idempotencyKey 并发提交：无论结果如何仅 1 张订单、1 条 Outbox；</li>
 *   <li>HTTP 端点：成功 200 / 库存不足 409(B2001) 契约。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderCheckoutConcurrencyTest {

    private static final String TENANT_ID = "tenant_yuzhuang_001";
    private static final String SPU_NAME = "于庄小磨香油";
    private static final BigDecimal UNIT_PRICE = new BigDecimal("45.00");

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;

    /** 每个用例前清空全部业务表并重置租户上下文，保证用例隔离。 */
    @BeforeEach
    void cleanTables() {
        TenantContext.clear();
        orderItemMapper.delete(null);
        orderMapper.delete(null);
        outboxEventMapper.delete(null);
        productSkuMapper.delete(null);
    }

    // ============================================================
    // (a)(b)(c) 高并发防超卖：30 线程抢 10 件
    // ============================================================

    @Test
    void concurrentCheckout_30ThreadsOver10Stock_noOversell() throws Exception {
        Long skuId = seedSku(10);
        int total = 30;

        ExecutorService pool = Executors.newFixedThreadPool(total);
        try {
            CountDownLatch ready = new CountDownLatch(total);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(total);

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger stockOutCount = new AtomicInteger();
            AtomicReference<String> unexpected = new AtomicReference<>();

            for (int i = 0; i < total; i++) {
                final String idempotencyKey = "idem_concurrent_" + UUID.randomUUID();
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        TenantContext.setTenantId(TENANT_ID);
                        OrderCheckoutResponse resp =
                                orderService.checkout(buildRequest(skuId), idempotencyKey);
                        // 成功线程必须返回 STOCK_CONFIRMED
                        if ("STOCK_CONFIRMED".equals(resp.getStatus())) {
                            successCount.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null, "unexpected status=" + resp.getStatus());
                        }
                    } catch (BusinessException e) {
                        if (ResultCode.INVENTORY_STOCK_OUT.getCode().equals(e.getCode())) {
                            stockOutCount.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null, "unexpected biz code=" + e.getCode());
                        }
                    } catch (Exception e) {
                        unexpected.compareAndSet(null,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        TenantContext.clear();
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("all checkout threads should finish within timeout")
                    .isTrue();
            pool.shutdown();

            // 恰好 10 单成功、20 单库存不足(409 B2001)
            assertThat(unexpected.get()).as("no unexpected failure: %s", unexpected.get()).isNull();
            assertThat(successCount.get()).isEqualTo(10);
            assertThat(stockOutCount.get()).isEqualTo(20);

            // 库存严格归零，绝不超卖
            ProductSku sku = productSkuMapper.selectById(skuId);
            assertThat(sku).isNotNull();
            assertThat(sku.getStock()).as("stock must be exactly 0").isZero();
            assertThat(sku.getVersion()).as("version must increment once per success").isEqualTo(10);

            // 恰好 10 张订单 + 10 条明细 + 10 条 Outbox 事件
            assertThat(orderMapper.selectCount(null)).isEqualTo(10L);
            assertThat(orderItemMapper.selectCount(null)).isEqualTo(10L);
            assertThat(outboxEventMapper.selectCount(null)).isEqualTo(10L);

            // Outbox 事件均为 ORDER / ORDER_CREATED / PENDING
            List<OutboxEvent> events = outboxEventMapper.selectList(null);
            assertThat(events).hasSize(10);
            assertThat(events).allSatisfy(e -> {
                assertThat(e.getAggregateType()).isEqualTo("ORDER");
                assertThat(e.getEventType()).isEqualTo("ORDER_CREATED");
                assertThat(e.getStatus()).isEqualTo("PENDING");
                assertThat(e.getPayload()).isNotBlank();
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================================================
    // (d) 同一 idempotencyKey 顺序重复提交 → 幂等回放，不重复扣减
    // ============================================================

    @Test
    void sameIdempotencyKey_duplicateSubmit_replaysSameOrder() {
        Long skuId = seedSku(5);
        TenantContext.setTenantId(TENANT_ID);
        try {
            String idempotencyKey = "idem_dup_" + UUID.randomUUID();
            OrderCheckoutRequest request = buildRequest(skuId);

            OrderCheckoutResponse first = orderService.checkout(request, idempotencyKey);
            assertThat(first.getStatus()).isEqualTo("STOCK_CONFIRMED");
            assertThat(first.getTotalAmount()).isEqualByComparingTo(UNIT_PRICE);
            assertThat(first.getExpireTime()).isNotNull();

            // 重复提交：回放同一订单号，且不新增订单/Outbox、不再扣库存
            OrderCheckoutResponse second = orderService.checkout(request, idempotencyKey);
            assertThat(second.getOrderNo()).isEqualTo(first.getOrderNo());
            assertThat(second.getStatus()).isEqualTo("STOCK_CONFIRMED");

            assertThat(productSkuMapper.selectById(skuId).getStock())
                    .as("stock deducted exactly once")
                    .isEqualTo(4);
            assertThat(orderMapper.selectCount(null)).isEqualTo(1L);
            assertThat(orderItemMapper.selectCount(null)).isEqualTo(1L);
            assertThat(outboxEventMapper.selectCount(null)).isEqualTo(1L);
        } finally {
            TenantContext.clear();
        }
    }

    // ============================================================
    // 同一 idempotencyKey 并发提交：无论各线程最终回放/冲突，仅落 1 单
    // ============================================================

    @Test
    void concurrentSameIdempotencyKey_onlyOneOrderAndOneOutbox() throws Exception {
        Long skuId = seedSku(30);
        int total = 20;

        ExecutorService pool = Executors.newFixedThreadPool(total);
        try {
            CountDownLatch ready = new CountDownLatch(total);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(total);

            AtomicInteger unexpected = new AtomicInteger();
            final String idempotencyKey = "idem_same_" + UUID.randomUUID();

            for (int i = 0; i < total; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        TenantContext.setTenantId(TENANT_ID);
                        orderService.checkout(buildRequest(skuId), idempotencyKey);
                    } catch (BusinessException e) {
                        // 并发同键的落败方允许 IDEMPOTENT_CONFLICT；其余均为异常
                        if (!ResultCode.IDEMPOTENT_CONFLICT.getCode().equals(e.getCode())) {
                            unexpected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    } finally {
                        TenantContext.clear();
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("all threads should finish within timeout")
                    .isTrue();
            pool.shutdown();

            assertThat(unexpected.get()).as("no unexpected failure").isZero();
            // 无论如何只落 1 张订单、1 条明细、1 条 Outbox，且库存只扣 1 件
            assertThat(orderMapper.selectCount(null)).isEqualTo(1L);
            assertThat(orderItemMapper.selectCount(null)).isEqualTo(1L);
            assertThat(outboxEventMapper.selectCount(null)).isEqualTo(1L);
            assertThat(productSkuMapper.selectById(skuId).getStock()).isEqualTo(29);
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================================================
    // HTTP 端点契约（成功 200 / 库存不足 409 B2001 / 幂等回放同单）
    // ============================================================

    @Test
    void checkoutEndpoint_success_returns200AndStockConfirmed() throws Exception {
        Long skuId = seedSku(3);
        String body = objectMapper.writeValueAsString(buildRequest(skuId));

        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/checkout")
                        .header(HeaderNames.X_TENANT_ID, TENANT_ID)
                        .header(HeaderNames.X_IDEMPOTENCY_KEY, "idem_http_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value("STOCK_CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(45.00))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.expireTime").isNumber())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"code\":\"00000\"");
    }

    @Test
    void checkoutEndpoint_stockOut_returns409B2001() throws Exception {
        Long skuId = seedSku(1);
        // 第一次下单成功，耗尽库存
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/checkout")
                        .header(HeaderNames.X_TENANT_ID, TENANT_ID)
                        .header(HeaderNames.X_IDEMPOTENCY_KEY, "idem_http_1_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(skuId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("STOCK_CONFIRMED"));

        // 第二次（不同幂等键）库存不足 → 409 / B2001
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/checkout")
                        .header(HeaderNames.X_TENANT_ID, TENANT_ID)
                        .header(HeaderNames.X_IDEMPOTENCY_KEY, "idem_http_2_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(skuId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void checkoutEndpoint_missingIdempotencyHeader_returns400A1001() throws Exception {
        Long skuId = seedSku(3);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/checkout")
                        .header(HeaderNames.X_TENANT_ID, TENANT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(skuId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 造一条指定库存的 SKU（如"于庄小磨香油"）并返回主键。 */
    private Long seedSku(int stock) {
        ProductSku sku = ProductSku.builder()
                .tenantId(TENANT_ID)
                .skuCode("SKU_" + UUID.randomUUID())
                .spuName(SPU_NAME)
                .price(UNIT_PRICE)
                .stock(stock)
                .version(0)
                // 与特产读接口契约对齐：在售 = ON_SALE（OrderServiceImpl 同样按 ON_SALE 校验）
                .status("ON_SALE")
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku.getId();
    }

    /** 构造购买 1 件"于庄小磨香油"的下单请求。 */
    private OrderCheckoutRequest buildRequest(Long skuId) {
        OrderItemRequest item = new OrderItemRequest(skuId, 1, UNIT_PRICE);
        ReceiverAddress address =
                new ReceiverAddress("张三", "13800000000", "河南省周口市鹿邑县试量镇于庄村");
        return new OrderCheckoutRequest(OrderSource.H5_PRIVATE, "于庄特产下单", List.of(item), address);
    }
}
