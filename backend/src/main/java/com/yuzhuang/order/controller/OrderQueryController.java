package com.yuzhuang.order.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.order.dto.OrderDetailResponse;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;
import com.yuzhuang.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询聚合接口（严格对齐 docs/api-spec.yaml /orders）。
 *
 * <p>端点契约（自安全强化起受保护，需 {@code Authorization: Bearer <JWT>}）：
 * <ul>
 *   <li>{@code GET /api/v1/orders}：分页查询<b>令牌 tenantId</b> 所属租户订单（状态/履约/渠道可选过滤）；</li>
 *   <li>{@code GET /api/v1/orders/{orderNo}}：按订单号查询详情（含明细行），
 *       不存在/跨租户映射 HTTP 404 + A1004。</li>
 * </ul>
 * 租户不再取 {@code X-Tenant-Id} 头（仅公开浏览/下单语义保留该头）；任意已认证角色可读本租户订单。
 * 非法枚举（status/fulfillmentStatus/orderSource）由 {@code GlobalExceptionHandler} 统一转为 400 + A1001。
 */
@Slf4j
@Tag(name = "订单查询", description = "订单分页列表与详情只读接口")
@RestController
@RequestMapping("/api/v1")
public class OrderQueryController {

    private final OrderQueryService orderQueryService;

    public OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @Operation(summary = "分页查询本租户订单列表（支持状态/履约状态/渠道过滤）")
    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderSummaryResponse>> listOrders(
            @RequestParam(value = "status", required = false) OrderStatus status,
            @RequestParam(value = "fulfillmentStatus", required = false) FulfillmentStatus fulfillmentStatus,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "orderSource", required = false) OrderSource orderSource,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[order-query] list tenantId={}, status={}, fulfillmentStatus={}, keyword={}, orderSource={}, page={}, pageSize={}",
                tenantId, status, fulfillmentStatus, keyword, orderSource, page, pageSize);
        return ApiResponse.success(orderQueryService.listOrders(
                tenantId, status, fulfillmentStatus, keyword, orderSource, page, pageSize));
    }

    @Operation(summary = "查询订单详情（含明细行）")
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderDetailResponse> getOrderDetail(
            @PathVariable("orderNo") String orderNo) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[order-query] detail tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderQueryService.getOrderDetail(tenantId, orderNo));
    }
}