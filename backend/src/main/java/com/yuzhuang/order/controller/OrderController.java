package com.yuzhuang.order.controller;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.order.dto.OrderCheckoutRequest;
import com.yuzhuang.order.dto.OrderCheckoutResponse;
import com.yuzhuang.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 特产交易订单接口（严格对齐 docs/api-spec.yaml /orders/checkout）。
 *
 * <p>Header 契约：
 * <ul>
 *   <li>{@code X-Tenant-Id}（必填，多租户标识，由 TenantContextFilter 注入上下文）；</li>
 *   <li>{@code X-Idempotency-Key}（必填，幂等键防重复点击）。</li>
 * </ul>
 * 库存不足/幂等冲突将映射为 HTTP 409（B2001 / B2002），参数校验失败为 400（A1001）。
 */
@Slf4j
@Tag(name = "特产订单", description = "特产下单交易接口（高并发防超卖 + 幂等保障）")
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "特产商品下单（CAS 防超卖 + 幂等保障）")
    @PostMapping("/orders/checkout")
    public ApiResponse<OrderCheckoutResponse> checkout(
            @RequestHeader(value = HeaderNames.X_TENANT_ID) String tenantId,
            @RequestHeader(value = HeaderNames.X_IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody OrderCheckoutRequest request) {
        log.debug("[order] checkout start tenantId={}, idempotencyKey={}", tenantId, idempotencyKey);
        return ApiResponse.success(orderService.checkout(request, idempotencyKey));
    }
}
