package com.yuzhuang.order.controller;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询聚合接口（严格对齐 docs/api-spec.yaml /orders）。
 *
 * <p>端点契约：
 * <ul>
 *   <li>{@code GET /api/v1/orders}：分页查询本租户订单（状态/履约状态/渠道可选过滤，
 *       Header {@code X-Tenant-Id} 缺省 global）；</li>
 *   <li>{@code GET /api/v1/orders/{orderNo}}：按订单号查询详情（含明细行），
 *       不存在/跨租户映射 HTTP 404 + A1004。</li>
 * </ul>
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
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @RequestParam(value = "status", required = false) OrderStatus status,
            @RequestParam(value = "fulfillmentStatus", required = false) FulfillmentStatus fulfillmentStatus,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "orderSource", required = false) OrderSource orderSource,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        log.debug("[order-query] list tenantId={}, status={}, fulfillmentStatus={}, keyword={}, orderSource={}, page={}, pageSize={}",
                tenantId, status, fulfillmentStatus, keyword, orderSource, page, pageSize);
        return ApiResponse.success(orderQueryService.listOrders(
                tenantId, status, fulfillmentStatus, keyword, orderSource, page, pageSize));
    }

    @Operation(summary = "查询订单详情（含明细行）")
    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderDetailResponse> getOrderDetail(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @PathVariable("orderNo") String orderNo) {
        log.debug("[order-query] detail tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderQueryService.getOrderDetail(tenantId, orderNo));
    }
}