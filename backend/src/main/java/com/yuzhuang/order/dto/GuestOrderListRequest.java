package com.yuzhuang.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 匿名批量本人订单查询请求（阶段 E 订单中心）。
 *
 * <p>客户端提交本机保存的「订单号 + 查询凭证」列表（最多 {@value #MAX_ITEMS} 条），
 * 服务端逐条校验凭证，仅对校验通过的订单返回脱敏摘要。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GuestOrderListRequest {

    /** 单次批量上限（防滥用） */
    public static final int MAX_ITEMS = 20;

    /** 待查询的订单凭证列表 */
    @NotEmpty(message = "查询列表不能为空")
    @Size(max = MAX_ITEMS, message = "单次最多查询 20 笔订单")
    @Valid
    private List<GuestOrderLookupRequest> items;
}
