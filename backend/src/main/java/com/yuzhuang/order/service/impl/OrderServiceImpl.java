package com.yuzhuang.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.entity.ProductSku;
import com.yuzhuang.inventory.service.InventoryService;
import com.yuzhuang.order.dto.OrderCheckoutRequest;
import com.yuzhuang.order.dto.OrderCheckoutResponse;
import com.yuzhuang.order.dto.OrderItemRequest;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.support.QueryTokens;
import com.yuzhuang.order.service.OrderService;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 特产下单交易编排实现。
 *
 * <p>事务边界：{@code checkout} 全程处于一个 {@code @Transactional} 中，
 * 依次完成幂等检查 → CAS 扣库存 → 订单/明细落库 → Outbox 事件落库，
 * 任一步失败整体回滚（rollbackFor = Exception.class）。
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    /** 订单支付超时时长：30 分钟 */
    private static final long PAYMENT_TIMEOUT_MILLIS = 30L * 60 * 1000L;

    /** Outbox 事件常量 */
    private static final String AGGREGATE_TYPE_ORDER = "ORDER";
    private static final String EVENT_TYPE_ORDER_CREATED = "ORDER_CREATED";
    private static final String OUTBOX_STATUS_PENDING = "PENDING";

    /** SKU 在售状态（与特产读接口契约 status = 'ON_SALE' 对齐，见 ProductSkuMapper） */
    private static final String SKU_STATUS_ON_SALE = "ON_SALE";

    /** 订单号序列（进程内自增，配合毫秒时间戳保证唯一） */
    private static final AtomicLong ORDER_SEQ =
            new AtomicLong(ThreadLocalRandom.current().nextInt(1000, 9000));

    private static final DateTimeFormatter ORDER_NO_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderItemMapper orderItemMapper,
                            OutboxEventMapper outboxEventMapper,
                            InventoryService inventoryService,
                            ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCheckoutResponse checkout(OrderCheckoutRequest request, String idempotencyKey) {
        String tenantId = TenantContext.getTenantId();

        // 1. 幂等前置校验：同租户同幂等键已有有效订单 → 直接回放，避免重复扣减与重复发事件
        Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, tenantId)
                .eq(Order::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (isReplayable(existing.getStatus())) {
                log.info("[order] idempotent replay orderNo={}, tenantId={}", existing.getOrderNo(), tenantId);
                return toResponse(existing, null);
            }
            log.warn("[order] idempotency conflict on status={}, tenantId={}", existing.getStatus(), tenantId);
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT);
        }

        // 2. 生成业务订单号 + 本人订单查询凭证（明文仅返回一次，库中只存哈希）
        String orderNo = generateOrderNo();
        String queryToken = QueryTokens.generate();
        LocalDateTime now = LocalDateTime.now();

        // 3. 逐项 CAS 扣库存 + 计价（以数据库 SKU 实时价为唯一计价依据）
        List<OrderItem> items = new ArrayList<>(request.getItems().size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemRequest itemReq : request.getItems()) {
            ProductSku sku = inventoryService.getSku(itemReq.getSkuId());
            if (sku == null || !SKU_STATUS_ON_SALE.equals(sku.getStatus())) {
                throw new BusinessException(ResultCode.INVENTORY_STOCK_OUT);
            }
            // CAS 扣减：影响行数为 0 由 InventoryService 抛 INVENTORY_STOCK_OUT
            inventoryService.deductStock(sku.getId(), itemReq.getQuantity());

            BigDecimal unitPrice = sku.getPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            items.add(OrderItem.builder()
                    .orderNo(orderNo)
                    .skuId(sku.getId())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());
        }

        // 4. 订单落库（idempotency_key 唯一索引为并发防重最后一道兜底）
        Order order = Order.builder()
                .tenantId(tenantId)
                .orderNo(orderNo)
                .idempotencyKey(idempotencyKey)
                .orderSource(request.getOrderSource())
                .totalAmount(totalAmount)
                .status(OrderStatus.STOCK_CONFIRMED)
                .fulfillmentStatus(FulfillmentStatus.PENDING)
                .recipientName(request.getReceiverAddress().getRecipientName())
                .recipientPhone(request.getReceiverAddress().getPhone())
                .detailedAddress(request.getReceiverAddress().getDetailedAddress())
                .remark(request.getRemark())
                .queryTokenHash(QueryTokens.hash(queryToken))
                .createdAt(now)
                .build();
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发同键恰同时通过前置检查：唯一索引拦截，整体回滚（含已扣库存）
            log.warn("[order] concurrent duplicate idempotencyKey={}, tenantId={}", idempotencyKey, tenantId);
            throw new BusinessException(ResultCode.IDEMPOTENT_CONFLICT);
        }

        // 5. 明细落库
        for (OrderItem item : items) {
            orderItemMapper.insert(item);
        }

        // 6. 构建并写入 Outbox 事件（与订单同事务，状态 PENDING）
        outboxEventMapper.insert(buildOutboxEvent(order, items));

        log.info("[order] checkout success orderNo={}, amount={}, tenantId={}",
                orderNo, totalAmount, tenantId);
        return toResponse(order, queryToken);
    }

    /** 既有订单是否可直接回放（非终态失败/已取消订单即可） */
    private boolean isReplayable(OrderStatus status) {
        return status != null && status != OrderStatus.CANCELLED;
    }

    /** 组装响应：下单成功 status=STOCK_CONFIRMED，expireTime = 创建时刻 + 支付超时。 */
    private OrderCheckoutResponse toResponse(Order order, String queryToken) {
        LocalDateTime createdAt = order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now();
        long expireTime = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                + PAYMENT_TIMEOUT_MILLIS;
        return OrderCheckoutResponse.builder()
                .orderNo(order.getOrderNo())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .expireTime(expireTime)
                .queryToken(queryToken)
                .build();
    }

    /** 构建 Outbox 事件（聚合 ORDER / 事件 ORDER_CREATED / PENDING）。 */
    private OutboxEvent buildOutboxEvent(Order order, List<OrderItem> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", EVENT_TYPE_ORDER_CREATED);
        payload.put("orderNo", order.getOrderNo());
        payload.put("tenantId", order.getTenantId());
        payload.put("orderSource", order.getOrderSource().name());
        payload.put("totalAmount", order.getTotalAmount());
        payload.put("status", order.getStatus().name());

        List<Map<String, Object>> itemPayloads = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", item.getSkuId());
            m.put("quantity", item.getQuantity());
            m.put("unitPrice", item.getUnitPrice());
            m.put("subtotal", item.getSubtotal());
            itemPayloads.add(m);
        }
        payload.put("items", itemPayloads);
        payload.put("createdAt", order.getCreatedAt().toString());

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[order] serialize outbox payload failed, orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "订单事件序列化失败");
        }

        return OutboxEvent.builder()
                .tenantId(order.getTenantId())
                .aggregateType(AGGREGATE_TYPE_ORDER)
                .aggregateId(order.getOrderNo())
                .eventType(EVENT_TYPE_ORDER_CREATED)
                .payload(payloadJson)
                .status(OUTBOX_STATUS_PENDING)
                .retryCount(0)
                .createdAt(order.getCreatedAt())
                .build();
    }

    /**
     * 生成业务订单号：ORD + yyyyMMddHHmmssSSS + 4 位进程内自增序号。
     * 进程内由 AtomicLong 保证并发唯一；跨实例极端碰撞由 order_no 唯一索引兜底。
     */
    private String generateOrderNo() {
        long seq = ORDER_SEQ.incrementAndGet() % 10_000L;
        return "ORD" + ORDER_NO_TS.format(LocalDateTime.now()) + String.format("%04d", seq);
    }
}
