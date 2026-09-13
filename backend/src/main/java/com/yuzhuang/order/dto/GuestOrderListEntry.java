package com.yuzhuang.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 批量本人订单查询的单条结果（阶段 E）。
 *
 * <p>{@code valid=false} 表示该凭证校验未通过（订单不存在或凭证错误），
 * 此时 {@code order} 为 null 且 {@code error} 给出可读原因；
 * <b>不会</b>区分“订单不存在”与“凭证错误”，避免订单号枚举。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestOrderListEntry {

    /** 订单号（原样回显，便于前端对应本地记录） */
    private String orderNo;

    /** 凭证是否校验通过 */
    private boolean valid;

    /** 未通过时的可读原因 */
    private String error;

    /** 通过时的脱敏订单状态 */
    private GuestOrderLookupResponse order;
}
