package com.yuzhuang.web;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.mapper.ProductSkuMapper;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.support.QueryTokens;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单闭环契约测试（阶段 D/E）：
 * 发货登记物流、未支付订单取消（含库存回补）、匿名本人订单查询凭证、政府 CSV 导出。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderLifecycleContractTest extends WebAuthTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;

    @BeforeEach
    void clean() {
        orderItemMapper.delete(null);
        orderMapper.delete(null);
        productSkuMapper.delete(null);
    }

    @Test
    void shipWithLogistics_persistsCarrierAndTrackingNo() throws Exception {
        Order order = seedOrder("ORD-SHIP-1", OrderStatus.PROCESSING, FulfillmentStatus.READY);

        mockMvc.perform(post("/api/v1/orders/" + order.getOrderNo() + "/ship")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰速运\",\"trackingNo\":\"SF1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.fulfillmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.carrier").value("顺丰速运"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF1234567890"));

        Order stored = orderMapper.selectById(order.getId());
        assertThat(stored.getCarrier()).isEqualTo("顺丰速运");
        assertThat(stored.getTrackingNo()).isEqualTo("SF1234567890");
        assertThat(stored.getShippedAt()).isNotNull();
    }

    @Test
    void shipWithoutBody_stillWorksForBackwardCompatibility() throws Exception {
        Order order = seedOrder("ORD-SHIP-2", OrderStatus.PROCESSING, FulfillmentStatus.READY);
        mockMvc.perform(post("/api/v1/orders/" + order.getOrderNo() + "/ship")
                        .header(HeaderNames.AUTHORIZATION, villageBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfillmentStatus").value("SHIPPED"));
    }

    @Test
    void cancelRestoresStockAndMarksCancelled() throws Exception {
        ProductSku sku = seedSku("SKU-CANCEL", 10);
        Order order = seedOrder("ORD-CANCEL-1", OrderStatus.STOCK_CONFIRMED, FulfillmentStatus.PENDING);
        seedItem(order.getOrderNo(), sku.getId(), 3);

        mockMvc.perform(post("/api/v1/orders/" + order.getOrderNo() + "/cancel")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户主动取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        ProductSku after = productSkuMapper.selectById(sku.getId());
        assertThat(after.getStock()).isEqualTo(13); // 10 + 回补 3
        Order stored = orderMapper.selectById(order.getId());
        assertThat(stored.getCancelReason()).isEqualTo("用户主动取消");
        assertThat(stored.getCloseReason()).isEqualTo("MANUAL_CANCEL");
    }

    @Test
    void cancelPaidOrder_conflicts409() throws Exception {
        Order order = seedOrder("ORD-CANCEL-2", OrderStatus.PROCESSING, FulfillmentStatus.PICKING);
        mockMvc.perform(post("/api/v1/orders/" + order.getOrderNo() + "/cancel")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2003"));
    }

    @Test
    void guestLookup_requiresMatchingToken_andMasksPii() throws Exception {
        String token = "guest-query-token-1234567890";
        Order order = seedOrder("ORD-GUEST-1", OrderStatus.PROCESSING, FulfillmentStatus.SHIPPED);
        Order withToken = new Order();
        withToken.setId(order.getId());
        withToken.setQueryTokenHash(QueryTokens.hash(token));
        withToken.setCarrier("中通快递");
        withToken.setTrackingNo("ZT99887766");
        withToken.setShippedAt(LocalDateTime.now());
        orderMapper.updateById(withToken);

        // 正确凭证：200 且脱敏
        mockMvc.perform(post("/api/v1/orders/guest/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-GUEST-1\",\"queryToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.trackingNo").value("ZT99887766"))
                .andExpect(jsonPath("$.data.recipientPhoneMasked").value("138****00"));

        // 错误凭证：404（不区分不存在/凭证错误，防订单号枚举）
        mockMvc.perform(post("/api/v1/orders/guest/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-GUEST-1\",\"queryToken\":\"wrong-token\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));
    }

    @Test
    void guestLookup_missingToken_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/orders/guest/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A1001"));
    }

    @Test
    void guestCancel_restoresStockAndMarksCancelled() throws Exception {
        String token = "guest-cancel-token-0987654321";
        ProductSku sku = seedSku("SKU-GCANCEL", 5);
        Order order = seedOrder("ORD-GCANCEL-1", OrderStatus.STOCK_CONFIRMED, FulfillmentStatus.PENDING);
        seedItem(order.getOrderNo(), sku.getId(), 2);
        Order withToken = new Order();
        withToken.setId(order.getId());
        withToken.setQueryTokenHash(QueryTokens.hash(token));
        orderMapper.updateById(withToken);

        mockMvc.perform(post("/api/v1/orders/guest/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-GCANCEL-1\",\"queryToken\":\"" + token
                                + "\",\"reason\":\"买错了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("买错了"));

        assertThat(productSkuMapper.selectById(sku.getId()).getStock()).isEqualTo(7); // 5 + 2

        // 凭证错误：404，且不得改动订单
        mockMvc.perform(post("/api/v1/orders/guest/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-GCANCEL-1\",\"queryToken\":\"bad\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));
    }

    @Test
    void govCsvExport_platformAdminAllowed_villageForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/gov/export")
                        .header(HeaderNames.AUTHORIZATION, bearer("PLATFORM_ADMIN", "tenant_platform_000")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("gov-summary-")))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")));

        mockMvc.perform(get("/api/v1/gov/export")
                        .header(HeaderNames.AUTHORIZATION, villageBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
    }

    // ------------------------------------------------------------------ 种子

    private Order seedOrder(String orderNo, OrderStatus status, FulfillmentStatus fulfillment) {
        Order order = Order.builder()
                .tenantId(TENANT_A)
                .orderNo(orderNo)
                .idempotencyKey("idem_" + orderNo)
                .orderSource(OrderSource.H5_PRIVATE)
                .totalAmount(new BigDecimal("90.00"))
                .status(status)
                .fulfillmentStatus(fulfillment)
                .paidAt(status == OrderStatus.PROCESSING ? LocalDateTime.now() : null)
                .payChannel(status == OrderStatus.PROCESSING ? "SANDBOX" : null)
                .recipientName("张三")
                .recipientPhone("13800000000")
                .detailedAddress("河南省周口市鹿邑县试量镇于庄村1号")
                .createdAt(LocalDateTime.now())
                .build();
        orderMapper.insert(order);
        return order;
    }

    private ProductSku seedSku(String skuCode, int stock) {
        ProductSku sku = ProductSku.builder()
                .tenantId(TENANT_A)
                .skuCode(skuCode)
                .spuName("测试商品")
                .price(new BigDecimal("10.00"))
                .stock(stock)
                .version(0)
                .status("ON_SALE")
                .createdAt(LocalDateTime.now())
                .build();
        productSkuMapper.insert(sku);
        return sku;
    }

    private void seedItem(String orderNo, Long skuId, int quantity) {
        orderItemMapper.insert(OrderItem.builder()
                .orderNo(orderNo)
                .skuId(skuId)
                .quantity(quantity)
                .unitPrice(new BigDecimal("10.00"))
                .subtotal(new BigDecimal("30.00"))
                .build());
    }
}
