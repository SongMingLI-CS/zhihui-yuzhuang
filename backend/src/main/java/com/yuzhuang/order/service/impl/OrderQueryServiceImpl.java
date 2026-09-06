package com.yuzhuang.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.order.dto.OrderDetailResponse;
import com.yuzhuang.order.dto.OrderItemResponse;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.entity.OrderItem;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.mapper.OrderItemMapper;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单查询聚合服务实现。
 *
 * <p>列表：按 {@code tenant_id = tenant} 硬过滤（订单不跨租户共享），
 * 支持状态/渠道可选过滤，按 {@code created_at} 倒序分页；分页采用
 * {@code LIMIT/OFFSET} 手工拼接（项目未启用 MyBatis-Plus 分页插件，保持轻量）。
 * 详情：按 {@code (tenant_id, orderNo)} 联合查询，防止跨租户越权，明细行随详情返回。
 */
@Service
public class OrderQueryServiceImpl implements OrderQueryService {

    /** 缺省租户标识（与 TenantContext / 契约缺省一致） */
    private static final String DEFAULT_TENANT_ID = "global";

    /** 分页缺省与上限 */
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public OrderQueryServiceImpl(OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    public PageResult<OrderSummaryResponse> listOrders(String tenantId, OrderStatus status,
                                                       OrderSource orderSource, int page, int pageSize) {
        String tenant = normalizeTenantId(tenantId);
        int p = page <= 0 ? DEFAULT_PAGE : page;
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getTenantId, tenant);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        if (orderSource != null) {
            wrapper.eq(Order::getOrderSource, orderSource);
        }

        // 先按干净条件统计总数（此时 wrapper 尚未附加 orderBy/LIMIT），
        // 再附加排序与分页片段执行列表查询，避免 COUNT 语句混入分页片段。
        long total = orderMapper.selectCount(wrapper);
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);

        long offset = (long) (p - 1) * size;
        List<Order> orders = orderMapper.selectList(
                wrapper.orderByDesc(Order::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + offset));

        List<OrderSummaryResponse> items = orders.stream().map(this::toSummary).toList();
        return PageResult.<OrderSummaryResponse>builder()
                .items(items)
                .page(p)
                .pageSize(size)
                .total(total)
                .totalPages(totalPages)
                .build();
    }

    @Override
    public OrderDetailResponse getOrderDetail(String tenantId, String orderNo) {
        String tenant = normalizeTenantId(tenantId);
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "订单号不能为空");
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getTenantId, tenant)
                .eq(Order::getOrderNo, orderNo.trim()));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        List<OrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, order.getOrderNo()));
        return toDetail(order, orderItems);
    }

    /** 租户规整：null/空白回退 {@code global}。 */
    private String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    /** 实体 → 列表摘要。 */
    private OrderSummaryResponse toSummary(Order order) {
        return OrderSummaryResponse.builder()
                .orderNo(order.getOrderNo())
                .orderSource(order.getOrderSource().name())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .recipientName(order.getRecipientName())
                .createdAt(order.getCreatedAt())
                .build();
    }

    /** 实体 + 明细 → 详情。 */
    private OrderDetailResponse toDetail(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(it -> OrderItemResponse.builder()
                        .skuId(it.getSkuId())
                        .quantity(it.getQuantity())
                        .unitPrice(it.getUnitPrice())
                        .subtotal(it.getSubtotal())
                        .build())
                .toList();
        return OrderDetailResponse.builder()
                .orderNo(order.getOrderNo())
                .orderSource(order.getOrderSource().name())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .recipientName(order.getRecipientName())
                .recipientPhone(order.getRecipientPhone())
                .detailedAddress(order.getDetailedAddress())
                .remark(order.getRemark())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }
}
