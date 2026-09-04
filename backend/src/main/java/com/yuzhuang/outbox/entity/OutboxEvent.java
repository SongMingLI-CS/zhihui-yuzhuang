package com.yuzhuang.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbox 发件箱实体（对应 t_outbox_event）。
 *
 * <p>订单创建与事件同事务落库，保证"订单已建"与"事件已记录"强一致，
 * 由后台可靠投递任务扫描 {@code PENDING} 记录推送到下游（Stream/队列）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_outbox_event")
public class OutboxEvent {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 多租户标识 */
    private String tenantId;

    /** 聚合类型（ORDER） */
    private String aggregateType;

    /** 聚合标识（order_no） */
    private String aggregateId;

    /** 事件类型（ORDER_CREATED） */
    private String eventType;

    /** 事件 JSON 载荷 */
    private String payload;

    /** 投递状态：PENDING / PUBLISHED / PROCESSED / FAILED（见 com.yuzhuang.outbox.enums.OutboxStatus） */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
