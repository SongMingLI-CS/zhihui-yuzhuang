package com.yuzhuang.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderFulfillmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单履约写服务实现。
 *
 * <p>履约状态机（出库流水轴，独立于交易 {@code status}）：
 * <pre>
 *   ship       : READY    -> SHIPPED   （一键出库）
 *   markReady  : PICKING  -> READY     （拣货完成，进入待出库队列）
 *   recover    : ABNORMAL -> PICKING   （异常单恢复拣货）
 * </pre>
 * 全部采用条件 {@code UPDATE ... WHERE tenant_id=? AND order_no=? AND fulfillment_status=?}，
 * 影响行数为 0 说明单已被并发展开或处于非期望状态，统一抛 {@code B2003}，防止并发重复推进。
 * 租户隔离：订单不跨租户共享，定位失败一律 {@code A1004}。
 */
@Slf4j
@Service
public class OrderFulfillmentServiceImpl implements OrderFulfillmentService {

    private final OrderMapper orderMapper;

    public OrderFulfillmentServiceImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSummaryResponse shipOrder(String tenantId, String orderNo) {
        return transition(tenantId, orderNo, FulfillmentStatus.READY, FulfillmentStatus.SHIPPED, "出库");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSummaryResponse markReady(String tenantId, String orderNo) {
        return transition(tenantId, orderNo, FulfillmentStatus.PICKING, FulfillmentStatus.READY, "标记待出库");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSummaryResponse recoverFromAbnormal(String tenantId, String orderNo) {
        return transition(tenantId, orderNo, FulfillmentStatus.ABNORMAL, FulfillmentStatus.PICKING, "恢复拣货");
    }

    /** 通用推进：仅允许 {@code from → to}，条件更新影响行数为 0 时判定并发/状态不符。 */
    private OrderSummaryResponse transition(String tenantId, String orderNo,
                                            FulfillmentStatus from, FulfillmentStatus to, String action) {
        String tenant = TenantContext.normalizeTenantId(tenantId);
        String no = (orderNo == null || orderNo.isBlank()) ? null : orderNo.trim();
        if (no == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "订单号不能为空");
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, tenant)
                .eq(Order::getOrderNo, no));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getTenantId, tenant)
                .eq(Order::getOrderNo, no)
                .eq(Order::getFulfillmentStatus, from)
                .set(Order::getFulfillmentStatus, to));
        if (rows == 0) {
            log.warn("[order] {} conflict orderNo={}, tenantId={}, current={}",
                    action, no, tenant, order.getFulfillmentStatus());
            throw new BusinessException(ResultCode.ORDER_STATE_CONFLICT,
                    action + "失败：订单当前状态为「" + label(order.getFulfillmentStatus()) + "」，请刷新后重试");
        }
        order.setFulfillmentStatus(to);
        log.info("[order] {} success orderNo={}, tenantId={}", action, no, tenant);
        return toSummary(order);
    }

    /** 履约状态中文标签（仅用于提示文案）。 */
    private String label(FulfillmentStatus s) {
        return switch (s) {
            case PENDING -> "待履约(PENDING)";
            case PICKING -> "拣货中(PICKING)";
            case READY -> "待出库(READY)";
            case SHIPPED -> "已出库(SHIPPED)";
            case ABNORMAL -> "异常(ABNORMAL)";
        };
    }


    /** 实体 → 推进后订单摘要。 */
    private OrderSummaryResponse toSummary(Order order) {
        return OrderSummaryResponse.builder()
                .orderNo(order.getOrderNo())
                .orderSource(order.getOrderSource().name())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .fulfillmentStatus(order.getFulfillmentStatus().name())
                .recipientName(order.getRecipientName())
                .createdAt(order.getCreatedAt())
                .carrier(order.getCarrier())
                .trackingNo(order.getTrackingNo())
                .shippedAt(order.getShippedAt())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSummaryResponse shipOrder(String tenantId, String orderNo, String carrier, String trackingNo) {
        OrderSummaryResponse summary = transition(tenantId, orderNo,
                FulfillmentStatus.READY, FulfillmentStatus.SHIPPED, "出库");
        boolean hasCarrier = carrier != null && !carrier.isBlank();
        boolean hasTracking = trackingNo != null && !trackingNo.isBlank();
        if (hasCarrier || hasTracking) {
            String tenant = TenantContext.normalizeTenantId(tenantId);
            LocalDateTime shippedAt = LocalDateTime.now();
            Order update = new Order();
            if (hasCarrier) {
                update.setCarrier(carrier.trim());
            }
            if (hasTracking) {
                update.setTrackingNo(trackingNo.trim());
            }
            update.setShippedAt(shippedAt);
            orderMapper.update(update, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getTenantId, tenant)
                    .eq(Order::getOrderNo, orderNo.trim()));
            summary.setCarrier(hasCarrier ? carrier.trim() : null);
            summary.setTrackingNo(hasTracking ? trackingNo.trim() : null);
            summary.setShippedAt(shippedAt);
            log.info("[order] ship with logistics orderNo={}, carrier={}, trackingNo={}",
                    orderNo, summary.getCarrier(), summary.getTrackingNo());
        }
        return summary;
    }
}