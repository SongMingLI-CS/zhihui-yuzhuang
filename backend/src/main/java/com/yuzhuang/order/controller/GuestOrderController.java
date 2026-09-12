package com.yuzhuang.order.controller;

import com.yuzhuang.common.api.ApiResponse;
import com.yuzhuang.order.dto.GuestOrderCancelRequest;
import com.yuzhuang.order.dto.GuestOrderLookupRequest;
import com.yuzhuang.order.dto.GuestOrderLookupResponse;
import com.yuzhuang.order.service.GuestOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名本人订单查询接口（阶段 E，公开端点）。
 *
 * <p>消费端需同时提供 {@code orderNo + queryToken}（下单时下发），服务端校验凭证后返回
 * <b>脱敏</b>订单状态与物流信息；仅凭订单号无法查询（返回 404 + A1004）。
 * 限流由网关按 {@code /api/v1/orders/**} 统一策略约束。
 */
@Slf4j
@Tag(name = "订单查询（匿名）", description = "本人订单查询（订单号 + 一次性查询凭证）")
@RestController
@RequestMapping("/api/v1")
public class GuestOrderController {

    private final GuestOrderService guestOrderService;

    public GuestOrderController(GuestOrderService guestOrderService) {
        this.guestOrderService = guestOrderService;
    }

    @Operation(summary = "本人订单查询（需订单号 + 查询凭证；返回脱敏信息）")
    @PostMapping("/orders/guest/lookup")
    public ApiResponse<GuestOrderLookupResponse> lookup(
            @Valid @RequestBody GuestOrderLookupRequest request) {
        log.debug("[guest-lookup] orderNo={}", request.getOrderNo());
        return ApiResponse.success(guestOrderService.lookup(request));
    }

    @Operation(summary = "本人取消未支付订单（需订单号 + 查询凭证；含库存回补）")
    @PostMapping("/orders/guest/cancel")
    public ApiResponse<GuestOrderLookupResponse> cancel(
            @Valid @RequestBody GuestOrderCancelRequest request) {
        log.debug("[guest-cancel] orderNo={}", request.getOrderNo());
        return ApiResponse.success(guestOrderService.cancel(request));
    }
}
