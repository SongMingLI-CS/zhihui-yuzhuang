package com.yuzhuang.audit.service;

/**
 * 审计日志服务。
 */
public interface AuditService {

    /**
     * 记录一条审计日志（失败不影响主流程，仅记录错误日志）。
     *
     * @param action     动作标识（如 TENANT_CREATE / USER_DISABLE / GOV_EXPORT）
     * @param targetType 目标类型（可为 null）
     * @param targetId   目标标识（可为 null）
     * @param detail     明细（应为脱敏文本）
     * @param success    是否成功
     */
    void record(String action, String targetType, String targetId, String detail, boolean success);
}
