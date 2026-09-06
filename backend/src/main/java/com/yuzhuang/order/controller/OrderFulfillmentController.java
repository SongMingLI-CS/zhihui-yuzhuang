package com.yuzhuang.order.controller;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.service.OrderFulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单履约接口（严格对齐 docs/api-spec.yaml /orders/{orderNo}/ship 等）。
 *
 * <p>端点契约（履约状态机，出库流水轴）：
 * <ul>
 *   <li>{@code POST /api/v1/orders/{orderNo}/ship}：一键出库 READY→SHIPPED；</li>
 *   <li>{@code POST /api/v1/orders/{orderNo}/mark-ready}：拣货完成 PICKING→READY；</li>
 *   <li>{@code POST /api/v1/orders/{orderNo}/recover}：异常单恢复 ABNORMAL→PICKING。</li>
 * </ul>
 * Header {@code X-Tenant-Id} 缺省 global；不存在/跨租户映射 HTTP 404 + A1004，
 * 状态不符/并发推进映射 HTTP 409 + B2003。
 */
@Slf4j
@Tag(name = "订单履约", description = "订单出库履约写接口")
@RestController
@RequestMapping("/api/v1")
public class OrderFulfillmentController {

    private final OrderFulfillmentService orderFulfillmentService;

    public OrderFulfillmentController(OrderFulfillmentService orderFulfillmentService) {
        this.orderFulfillmentService = orderFulfillmentService;
    }

    @Operation(summary = "订单一键出库（READY → SHIPPED）")
    @PostMapping("/orders/{orderNo}/ship")
    public ApiResponse<OrderSummaryResponse> ship(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @PathVariable("orderNo") String orderNo) {
        log.debug("[order] ship start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderFulfillmentService.shipOrder(tenantId, orderNo));
    }

    @Operation(summary = "拣货完成置为待出库（PICKING → READY）")
    @PostMapping("/orders/{orderNo}/mark-ready")
    public ApiResponse<OrderSummaryResponse> markReady(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @PathVariable("orderNo") String orderNo) {
        log.debug("[order] mark-ready start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderFulfillmentService.markReady(tenantId, orderNo));
    }

    @Operation(summary = "异常单恢复拣货（ABNORMAL → PICKING）")
    @PostMapping("/orders/{orderNo}/recover")
    public ApiResponse<OrderSummaryResponse> recover(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, defaultValue = TenantContext.DEFAULT_TENANT_ID)
            String tenantId,
            @PathVariable("orderNo") String orderNo) {
        log.debug("[order] recover start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderFulfillmentService.recoverFromAbnormal(tenantId, orderNo));
    }
}