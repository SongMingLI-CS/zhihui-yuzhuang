package com.yuzhuang.audit.service.impl;

import com.yuzhuang.audit.entity.AuditLog;
import com.yuzhuang.audit.mapper.AuditLogMapper;
import com.yuzhuang.audit.service.AuditService;
import com.yuzhuang.auth.context.AuthContext;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.common.context.TenantContext;
import com.yuzhuang.common.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务实现：从当前认证上下文自动补齐操作人/角色/租户/链路 ID。
 */
@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(String action, String targetType, String targetId, String detail, boolean success) {
        try {
            AuthPrincipal principal = AuthContext.get();
            AuditLog log = AuditLog.builder()
                    .tenantId(tenantId(principal))
                    .actorUserId(principal == null ? null : principal.getUserId())
                    .actorUsername(principal == null ? null : principal.getUsername())
                    .actorRole(principal == null ? null : principal.getRole())
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(truncate(detail))
                    .requestId(TraceContext.getRequestId())
                    .result(success ? "SUCCESS" : "FAILED")
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogMapper.insert(log);
        } catch (Exception ex) {
            // 审计失败不阻断业务，但必须记录到应用日志
            log.error("[audit] 审计日志写入失败 action={} target={}/{}", action, targetType, targetId, ex);
        }
    }

    private String tenantId(AuthPrincipal principal) {
        String authTenant = principal == null ? null : principal.getTenantId();
        return TenantContext.normalizeTenantId(
                authTenant != null ? authTenant : TenantContext.getTenantId());
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 1000 ? detail : detail.substring(0, 1000);
    }
}
