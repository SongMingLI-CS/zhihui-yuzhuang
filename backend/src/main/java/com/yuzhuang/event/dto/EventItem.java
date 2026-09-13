package com.yuzhuang.event.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 真实业务事件条目（阶段 D：来自 Outbox 发件箱的事件流）。
 *
 * <p>数据源为与订单同事务落库的 {@code t_outbox_event}，故为真实事件；
 * 不含收货人姓名/手机号/地址等 PII。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventItem {

    /** 事件主键（可用作 SSE/轮询游标） */
    private Long id;

    /** 归属租户 */
    private String tenantId;

    /** 事件类型：ORDER_CREATED / ORDER_PAID / ORDER_CANCELLED */
    private String eventType;

    /** 聚合标识（订单号） */
    private String aggregateId;

    /** 事件中文摘要（由事件类型与载荷派生，便于大屏展示） */
    private String summary;

    /** 投递状态：PENDING / PUBLISHED / PROCESSED / FAILED */
    private String status;

    /** 事件发生时间（ISO 字符串） */
    private String createdAt;

    /** 事件载荷（JSON，已截断至 1000 字符，仅授权角色可见） */
    private String payload;
}
