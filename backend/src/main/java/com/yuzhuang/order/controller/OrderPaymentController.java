package com.yuzhuang.order.controller;

import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.order.dto.PaymentSandboxPayRequest;
import com.yuzhuang.order.dto.PaymentSandboxResponse;
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
 * 支付闭环端点（严格对齐 docs/api-spec.yaml /orders/{orderNo}/pay/sandbox）。
 *
 * <p>当前为<b>演示沙箱通道</b>：模拟支付渠道回调成功，将 STOCK_CONFIRMED → PROCESSING（已支付进入履约），
 * 并记录渠道流水号用于幂等。真实支付通道接入后，由对应 Adapter 在网关回调处调用
 * {@link OrderPaymentClosureService#sandboxPay} 并完成验签（见 docs/payment-closeout-design.md）。
 *
 * <p>该端点纳入通用访问矩阵（需 COOPERATIVE/VILLAGE 的 JWT），仅作演示/联调入口。
 */
@Slf4j
@Tag(name = "订单支付", description = "支付成功入账（演示沙箱）")
@RestController
@RequestMapping("/api/v1")
public class OrderPaymentController {

    private final OrderPaymentClosureService orderPaymentClosureService;

    public OrderPaymentController(OrderPaymentClosureService orderPaymentClosureService) {
        this.orderPaymentClosureService = orderPaymentClosureService;
    }

    @Operation(summary = "模拟支付成功（沙箱）→ 订单 STOCK_CONFIRMED → PROCESSING")
    @PostMapping("/orders/{orderNo}/pay/sandbox")
    public ApiResponse<PaymentSandboxResponse> sandboxPay(
            @PathVariable("orderNo") String orderNo,
            @Valid @RequestBody PaymentSandboxPayRequest request) {
        String tenantId = AuthContext.require().getTenantId();
        log.debug("[order-pay] sandbox pay orderNo={}, channel={}", orderNo, request.getChannel());
        return ApiResponse.success(orderPaymentClosureService.sandboxPay(tenantId, orderNo, request));
    }
}
