package com.yuzhuang.audit.entity;

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
 * 操作审计日志（对应 t_audit_log）。
 *
 * <p>记录管理操作、政府下钻/导出、AI 审批等敏感行为，供合规审计与追责。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_audit_log")
public class AuditLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属租户 */
    private String tenantId;

    /** 操作人用户主键 */
    private Long actorUserId;

    /** 操作人用户名 */
    private String actorUsername;

    /** 操作人角色 */
    private String actorRole;

    /** 动作：TENANT_CREATE / USER_DISABLE / GOV_EXPORT 等 */
    private String action;

    /** 目标类型 */
    private String targetType;

    /** 目标标识 */
    private String targetId;

    /** 明细（脱敏后的补充信息） */
    private String detail;

    /** 链路 ID */
    private String requestId;

    /** 结果：SUCCESS / FAILED */
    private String result;

    /** 时间 */
    private LocalDateTime createdAt;
}
