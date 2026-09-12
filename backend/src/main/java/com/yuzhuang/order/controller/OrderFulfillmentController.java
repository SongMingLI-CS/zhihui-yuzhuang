package com.yuzhuang.order.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.order.dto.OrderCancelRequest;
import com.yuzhuang.order.dto.OrderShipRequest;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.service.OrderFulfillmentService;
import com.yuzhuang.order.service.OrderPaymentClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单履约/取消接口（严格对齐 docs/api-spec.yaml /orders/{orderNo}/ship 等）。
 *
 * <ul>
 *   <li>{@code POST /orders/{orderNo}/ship}：READY→SHIPPED；可选请求体
 *       {@code {carrier, trackingNo}} 登记承运商与物流单号（阶段 E/C）；</li>
 *   <li>{@code POST /orders/{orderNo}/mark-ready}：PICKING→READY；</li>
 *   <li>{@code POST /orders/{orderNo}/recover}：ABNORMAL→PICKING；</li>
 *   <li>{@code POST /orders/{orderNo}/cancel}：未支付订单取消（CANCELLED + 库存回补，阶段 E）。</li>
 * </ul>
 * 数据范围取令牌 tenantId；不存在/跨租户 404 + A1004，状态不符 409 + B2003。
 */
@Slf4j
@Tag(name = "订单履约", description = "订单出库履约与取消写接口")
@RestController
@RequestMapping("/api/v1")
public class OrderFulfillmentController {

    private final OrderFulfillmentService orderFulfillmentService;
    private final OrderPaymentClosureService orderPaymentClosureService;

    public OrderFulfillmentController(OrderFulfillmentService orderFulfillmentService,
                                      OrderPaymentClosureService orderPaymentClosureService) {
        this.orderFulfillmentService = orderFulfillmentService;
        this.orderPaymentClosureService = orderPaymentClosureService;
    }

    @Operation(summary = "订单一键出库（READY → SHIPPED，可携带物流单号）")
    @PostMapping("/orders/{orderNo}/ship")
    public ApiResponse<OrderSummaryResponse> ship(
            @PathVariable("orderNo") String orderNo,
            @RequestBody(required = false) @Valid OrderShipRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        String carrier = request == null ? null : request.getCarrier();
        String trackingNo = request == null ? null : request.getTrackingNo();
        log.debug("[order] ship start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(
                orderFulfillmentService.shipOrder(tenantId, orderNo, carrier, trackingNo));
    }

    @Operation(summary = "拣货完成置为待出库（PICKING → READY）")
    @PostMapping("/orders/{orderNo}/mark-ready")
    public ApiResponse<OrderSummaryResponse> markReady(
            @PathVariable("orderNo") String orderNo) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[order] mark-ready start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderFulfillmentService.markReady(tenantId, orderNo));
    }

    @Operation(summary = "异常单恢复拣货（ABNORMAL → PICKING）")
    @PostMapping("/orders/{orderNo}/recover")
    public ApiResponse<OrderSummaryResponse> recover(
            @PathVariable("orderNo") String orderNo) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[order] recover start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderFulfillmentService.recoverFromAbnormal(tenantId, orderNo));
    }

    @Operation(summary = "取消未支付订单（CANCELLED + 库存回补）")
    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<OrderSummaryResponse> cancel(
            @PathVariable("orderNo") String orderNo,
            @RequestBody(required = false) @Valid OrderCancelRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        String reason = request == null ? null : request.getReason();
        log.debug("[order] cancel start tenantId={}, orderNo={}", tenantId, orderNo);
        return ApiResponse.success(orderPaymentClosureService.cancelOrder(tenantId, orderNo, reason));
    }
}
