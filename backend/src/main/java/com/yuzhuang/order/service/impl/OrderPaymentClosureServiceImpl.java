package com.yuzhuang.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.inventory.service.InventoryService;
import com.yuzhuang.order.dto.PaymentSandboxPayRequest;
import com.yuzhuang.order.dto.PaymentSandboxResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderPaymentClosureService;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.enums.OutboxStatus;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单支付/超时关单领域服务实现。
 *
 * <p>核心一致性策略：
 * <ul>
 *   <li>关单使用「条件状态门」{@code UPDATE ... WHERE status='STOCK_CONFIRMED' AND paid_at IS NULL}，
 *       并发/多实例下<b>恰好一个</b>线程影响 1 行并负责回补库存，其余返回 0 直接跳过 → 回补幂等；</li>
 *   <li>支付同理（WHERE 限制 STOCK_CONFIRMED + 未支付），并接受渠道 tradeNo 幂等重放；
 *       重复且流水号不一致视为异常支付 409 拒绝；</li>
 *   <li>库存回补与状态流转、Outbox 事件同处一个事务（{@link Transactional}）。</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderPaymentClosureServiceImpl implements OrderPaymentClosureService {

    private static final String AGGREGATE_TYPE_ORDER = "ORDER";
    private static final String EVENT_ORDER_PAID = "ORDER_PAID";
    private static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";
    private static final String CLOSE_REASON_PAY_TIMEOUT = "PAY_TIMEOUT";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    @Value("${yuzhuang.order.payment-timeout-minutes:30}")
    private long paymentTimeoutMinutes;

    public OrderPaymentClosureServiceImpl(OrderMapper orderMapper,
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
    public PaymentSandboxResponse sandboxPay(String tenantId, String orderNo, PaymentSandboxPayRequest request) {
        String channel = request.getChannel().trim();
        String tradeNo = request.getTradeNo().trim();

        Order order = findOrder(tenantId, orderNo);
        // 已支付：同渠道同流水号幂等回放成功；不同流水号视为重复支付拒绝
        if (order.getPaidAt() != null) {
            if (channel.equals(order.getPayChannel()) && tradeNo.equals(order.getPayTradeNo())) {
                log.info("[order-pay] idempotent replay orderNo={}, channel={}, tradeNo={}", orderNo, channel, tradeNo);
                return toResponse(order);
            }
            throw new BusinessException(ResultCode.ORDER_STATE_CONFLICT, "订单已支付，重复支付被拒绝");
        }
        if (order.getStatus() != OrderStatus.STOCK_CONFIRMED) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                throw new BusinessException(ResultCode.NOT_FOUND, "订单已关闭，无法支付");
            }
            throw new BusinessException(ResultCode.ORDER_STATE_CONFLICT, "当前订单状态不允许支付");
        }

        LocalDateTime paidAt = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getTenantId, tenantId)
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getStatus, OrderStatus.STOCK_CONFIRMED)
                .isNull(Order::getPaidAt)
                .set(Order::getStatus, OrderStatus.PROCESSING)
                .set(Order::getPayChannel, channel)
                .set(Order::getPayTradeNo, tradeNo)
                .set(Order::getPaidAt, paidAt));
        if (rows != 1) {
            Order latest = findOrder(tenantId, orderNo);
            if (latest.getPaidAt() != null
                    && channel.equals(latest.getPayChannel()) && tradeNo.equals(latest.getPayTradeNo())) {
                return toResponse(latest);
            }
            throw new BusinessException(ResultCode.ORDER_STATE_CONFLICT, "订单状态已变更，支付失败请刷新重试");
        }

        Order paid = findOrder(tenantId, orderNo);
        outboxEventMapper.insert(buildEvent(paid, EVENT_ORDER_PAID));
        log.info("[order-pay] paid orderNo={}, channel={}, tradeNo={}, paidAt={}",
                orderNo, channel, tradeNo, paidAt);
        return toResponse(paid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredOrders(int limit) {
        if (limit <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "关单批次上限必须大于 0");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(paymentTimeoutMinutes);
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.STOCK_CONFIRMED)
                .isNull(Order::getPaidAt)
                .le(Order::getCreatedAt, cutoff)
                .orderByAsc(Order::getCreatedAt)
                .last("LIMIT " + limit));

        int closed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Order order : expired) {
            String tenantId = order.getTenantId();
            String orderNo = order.getOrderNo();
            int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getTenantId, tenantId)
                    .eq(Order::getOrderNo, orderNo)
                    .eq(Order::getStatus, OrderStatus.STOCK_CONFIRMED)
                    .isNull(Order::getPaidAt)
                    .set(Order::getStatus, OrderStatus.CANCELLED)
                    .set(Order::getCancelledAt, now)
                    .set(Order::getCloseReason, CLOSE_REASON_PAY_TIMEOUT));
            if (rows != 1) {
                // 已被其他实例/线程关闭（条件状态门只允许一次成功），跳过避免重复回补
                continue;
            }
            releaseStock(orderNo);
            Order closedOrder = findOrder(tenantId, orderNo);
            outboxEventMapper.insert(buildEvent(closedOrder, EVENT_ORDER_CANCELLED));
            closed++;
            log.info("[order-close] closed expired orderNo={}, tenantId={}, reason={}", orderNo, tenantId, CLOSE_REASON_PAY_TIMEOUT);
        }
        if (closed > 0) {
            log.info("[order-close] batch done, closed={}, cutoff={}", closed, cutoff);
        }
        return closed;
    }

    /** 按明细行原子回补 SKU 库存（必须在关单同一事务内）。 */
    private void releaseStock(String orderNo) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo));
        for (OrderItem item : items) {
            inventoryService.restoreStock(item.getSkuId(), item.getQuantity());
        }
    }

    private Order findOrder(String tenantId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, tenantId)
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private PaymentSandboxResponse toResponse(Order order) {
        LocalDateTime paidAt = order.getPaidAt() != null ? order.getPaidAt() : LocalDateTime.now();
        return PaymentSandboxResponse.builder()
                .orderNo(order.getOrderNo())
                .status(order.getStatus().name())
                .channel(order.getPayChannel())
                .tradeNo(order.getPayTradeNo())
                .paidAt(paidAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    /** 构建 Outbox 事件（与状态流转同事务，PENDING 待可靠投递）。 */
    private OutboxEvent buildEvent(Order order, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("orderNo", order.getOrderNo());
        payload.put("tenantId", order.getTenantId());
        payload.put("orderSource", order.getOrderSource().name());
        payload.put("totalAmount", order.getTotalAmount());
        payload.put("status", order.getStatus().name());
        if (order.getPayChannel() != null) {
            payload.put("payChannel", order.getPayChannel());
            payload.put("payTradeNo", order.getPayTradeNo());
            payload.put("paidAt", String.valueOf(order.getPaidAt()));
        }
        if (order.getCloseReason() != null) {
            payload.put("closeReason", order.getCloseReason());
            payload.put("cancelledAt", String.valueOf(order.getCancelledAt()));
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "订单事件序列化失败");
        }
        return OutboxEvent.builder()
                .tenantId(order.getTenantId())
                .aggregateType(AGGREGATE_TYPE_ORDER)
                .aggregateId(order.getOrderNo())
                .eventType(eventType)
                .payload(payloadJson)
                .status(OutboxStatus.PENDING.name())
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
    }
}