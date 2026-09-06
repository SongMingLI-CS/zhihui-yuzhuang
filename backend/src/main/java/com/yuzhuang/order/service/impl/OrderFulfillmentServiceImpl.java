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

/**
 * 订单履约写服务实现（当前支持「一键出库」）。
 *
 * <p>出库语义：仅允许 {@code fulfillment_status = READY} 的订单推进到 {@code SHIPPED}；
 * 采用条件 {@code UPDATE ... WHERE tenant_id=? AND order_no=? AND fulfillment_status='READY'}，
 * 影响行数为 0 说明单已被并发出库或处于非就绪态，统一抛 {@code B2003}，防止并发重复出库。
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
        String tenant = normalizeTenantId(tenantId);
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
                .eq(Order::getFulfillmentStatus, FulfillmentStatus.READY)
                .set(Order::getFulfillmentStatus, FulfillmentStatus.SHIPPED));
        if (rows == 0) {
            log.warn("[order] ship conflict orderNo={}, tenantId={}, current={}",
                    no, tenant, order.getFulfillmentStatus());
            throw new BusinessException(ResultCode.ORDER_STATE_CONFLICT);
        }
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        log.info("[order] ship success orderNo={}, tenantId={}", no, tenant);
        return toSummary(order);
    }

    /** 租户规整：null/空白回退 {@code global}。 */
    private String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank())
                ? TenantContext.DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 实体 → 出库后订单摘要。 */
    private OrderSummaryResponse toSummary(Order order) {
        return OrderSummaryResponse.builder()
                .orderNo(order.getOrderNo())
                .orderSource(order.getOrderSource().name())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .fulfillmentStatus(order.getFulfillmentStatus().name())
                .recipientName(order.getRecipientName())
                .createdAt(order.getCreatedAt())
                .build();
    }
}