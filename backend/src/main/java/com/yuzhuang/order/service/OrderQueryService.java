package com.yuzhuang.order.service;

import com.yuzhuang.common.api.PageResult;
import com.yuzhuang.order.dto.OrderDetailResponse;
import com.yuzhuang.order.dto.OrderSummaryResponse;
import com.yuzhuang.order.enums.FulfillmentStatus;
import com.yuzhuang.order.enums.OrderSource;
import com.yuzhuang.order.enums.OrderStatus;

/**
 * 订单查询聚合服务（只读接口）。
 *
 * <p>列表语义：仅返回本租户（{@code tenant_id = tenant}）的订单，按创建时间倒序分页，
 * 支持按状态/履约状态/渠道过滤；租户隔离为硬过滤（订单不跨租户共享）。
 * 详情语义：按 {@code (tenant_id, orderNo)} 联合查询，不存在或跨租户抛
 * {@code ResultCode.NOT_FOUND(A1004)}，明细行随详情一并返回。
 */
public interface OrderQueryService {

    /**
     * 分页查询本租户订单列表。
     *
     * @param tenantId    租户标识（null/空白按 {@code global} 兜底）
     * @param status      订单状态过滤（可为 null 表示不过滤）
     * @param orderSource 渠道来源过滤（可为 null 表示不过滤）
     * @param page        页码（&lt;=0 时按 1 处理）
     * @param pageSize    每页条数（&lt;=0 按默认 10，上限 100）
     * @return 分页结果
     */
    PageResult<OrderSummaryResponse> listOrders(String tenantId, OrderStatus status,
                                                OrderSource orderSource, int page, int pageSize);

    /**
     * 分页查询本租户订单列表（履约状态维度，B 端履约看板使用）。
     *
     * @param tenantId          租户标识（null/空白按 {@code global} 兜底）
     * @param status            订单状态过滤（可为 null 表示不过滤）
     * @param fulfillmentStatus 履约状态过滤（可为 null 表示不过滤）
     * @param orderSource       渠道来源过滤（可为 null 表示不过滤）
     * @param page              页码（&lt;=0 时按 1 处理）
     * @param pageSize          每页条数（&lt;=0 按默认 10，上限 100）
     * @return 分页结果
     */
    PageResult<OrderSummaryResponse> listOrders(String tenantId, OrderStatus status,
                                                FulfillmentStatus fulfillmentStatus,
                                                OrderSource orderSource, int page, int pageSize);

    /**
     * 查询订单详情（含明细行）。
     *
     * @param tenantId 租户标识（null/空白按 {@code global} 兜底）
     * @param orderNo  业务订单号
     * @return 订单详情
     * @throws com.yuzhuang.common.exception.BusinessException 订单不存在或跨租户时抛 A1004
     */
    OrderDetailResponse getOrderDetail(String tenantId, String orderNo);
}