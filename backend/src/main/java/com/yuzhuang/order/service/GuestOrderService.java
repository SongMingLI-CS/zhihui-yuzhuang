package com.yuzhuang.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuang.common.enums.ResultCode;
import com.yuzhuang.common.exception.BusinessException;
import com.yuzhuang.order.dto.GuestOrderCancelRequest;
import com.yuzhuang.order.dto.GuestOrderLookupRequest;
import com.yuzhuang.order.dto.GuestOrderLookupResponse;
import com.yuzhuang.order.entity.Order;
import com.yuzhuang.order.mapper.OrderMapper;
import com.yuzhuang.order.service.OrderPaymentClosureService;
import com.yuzhuang.order.support.QueryTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

/**
 * 匿名本人订单查询（阶段 E）。
 *
 * <p>安全设计：订单号全局唯一，结合下单时下发的一次性查询凭证（PBKDF2 常量时间校验）
 * 才能读取；响应做脱敏（姓名/手机号/地址），不返回完整 PII。
 * 未命中统一返回 404 + A1004，不区分“订单不存在”与“凭证错误”，避免订单号枚举。
 */
@Slf4j
@Service
public class GuestOrderService {

    private final OrderMapper orderMapper;
    private final OrderPaymentClosureService orderPaymentClosureService;

    public GuestOrderService(OrderMapper orderMapper,
                             OrderPaymentClosureService orderPaymentClosureService) {
        this.orderMapper = orderMapper;
        this.orderPaymentClosureService = orderPaymentClosureService;
    }

    /** 校验凭证并返回脱敏订单状态。 */
    public GuestOrderLookupResponse lookup(GuestOrderLookupRequest request) {
        Order order = requireByToken(request.getOrderNo(), request.getQueryToken());
        return toResponse(order);
    }

    /**
     * 匿名本人取消未支付订单（阶段 E）：凭证校验通过后，复用租户侧取消逻辑
     * （条件状态门 + 库存回补 + Outbox 事件），再返回脱敏后的订单状态。
     */
    public GuestOrderLookupResponse cancel(GuestOrderCancelRequest request) {
        Order order = requireByToken(request.getOrderNo(), request.getQueryToken());
        orderPaymentClosureService.cancelOrder(order.getTenantId(), order.getOrderNo(),
                request.getReason());
        Order latest = orderMapper.selectById(order.getId());
        return toResponse(latest);
    }

    /** 凭证校验（订单号 + 一次性凭证）；不通过统一 404，避免订单号枚举。 */
    private Order requireByToken(String orderNoRaw, String tokenRaw) {
        String orderNo = orderNoRaw == null ? "" : orderNoRaw.trim();
        String token = tokenRaw == null ? "" : tokenRaw.trim();
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null || !QueryTokens.matches(token, order.getQueryTokenHash())) {
            log.warn("[guest-order] credential rejected orderNo={}", orderNo);
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在或查询凭证不正确");
        }
        return order;
    }

    /** 实体 → 脱敏响应。 */
    private GuestOrderLookupResponse toResponse(Order order) {
        return GuestOrderLookupResponse.builder()
                .orderNo(order.getOrderNo())
                .status(order.getStatus() == null ? null : order.getStatus().name())
                .fulfillmentStatus(order.getFulfillmentStatus() == null
                        ? null : order.getFulfillmentStatus().name())
                .totalAmount(order.getTotalAmount())
                .recipientNameMasked(maskName(order.getRecipientName()))
                .recipientPhoneMasked(maskPhone(order.getRecipientPhone()))
                .addressMasked(maskAddress(order.getDetailedAddress()))
                .carrier(order.getCarrier())
                .trackingNo(order.getTrackingNo())
                .shippedAt(toEpochMillis(order))
                .cancelReason(order.getCancelReason())
                .createdAt(order.getCreatedAt() == null ? null
                        : order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    private static Long toEpochMillis(Order order) {
        return order.getShippedAt() == null ? null
                : order.getShippedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 姓名脱敏：保留首字，其余以 * 代替（单字则整体为 *）。 */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.length() == 1) {
            return "*";
        }
        return trimmed.charAt(0) + "*".repeat(Math.min(trimmed.length() - 1, 3));
    }

    /** 手机号脱敏：保留前 3 位与后 2 位。 */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String trimmed = phone.trim();
        if (trimmed.length() < 7) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 2);
    }

    /** 地址脱敏：仅保留到县/区/镇一级，其余省略。 */
    static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String trimmed = address.trim();
        int cut = -1;
        for (String marker : new String[]{"区", "县", "镇", "乡", "街道"}) {
            int idx = trimmed.indexOf(marker);
            if (idx >= 0 && (cut < 0 || idx < cut)) {
                cut = idx;
            }
        }
        if (cut >= 0) {
            return trimmed.substring(0, cut + 1) + "…";
        }
        return trimmed.length() <= 6 ? trimmed + "…" : trimmed.substring(0, 6) + "…";
    }
}
