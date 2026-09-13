package com.yuzhuang.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuang.auth.context.AuthPrincipal;
import com.yuzhuang.auth.enums.UserRole;
import com.yuzhuang.event.dto.EventItem;
import com.yuzhuang.gov.service.GovScopeService;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 真实业务事件流服务（阶段 D）。
 *
 * <p>数据来源：{@code t_outbox_event}（订单事件与订单同事务落库，真实可靠）。
 * 租户范围由**已验证主体**推导：
 * <ul>
 *   <li>PLATFORM_ADMIN → 全域（{@code null}）；</li>
 *   <li>GOVERNMENT → 其 {@code t_gov_scope} 授权租户集合（ALL 授权 → 全域）；</li>
 *   <li>VILLAGE / COOPERATIVE → 仅本租户。</li>
 * </ul>
 * 无可见租户时直接返回空列表（不查库），避免越权与无效查询。
 */
@Slf4j
@Service
public class EventStreamService {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 30;
    private static final int PAYLOAD_MAX_CHARS = 1000;

    private final OutboxEventMapper outboxEventMapper;
    private final GovScopeService govScopeService;
    private final ObjectMapper objectMapper;

    public EventStreamService(OutboxEventMapper outboxEventMapper,
                              GovScopeService govScopeService,
                              ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.govScopeService = govScopeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析可见租户范围。
     *
     * @return {@code null} 表示全域；否则为可见租户集合（可能为空 = 无可见范围）
     */
    public Set<String> resolveScope(AuthPrincipal principal) {
        if (principal == null) {
            return Set.of();
        }
        String role = principal.getRole();
        if (UserRole.PLATFORM_ADMIN.name().equals(role)) {
            return null;
        }
        if (UserRole.GOVERNMENT.name().equals(role)) {
            GovScopeService.ScopeResolution scope = govScopeService.resolve(principal);
            return scope.all() ? null : new LinkedHashSet<>(scope.tenantIds());
        }
        // VILLAGE / COOPERATIVE：仅本租户
        return principal.getTenantId() == null ? Set.of() : Set.of(principal.getTenantId());
    }

    /** 拉取事件（afterId 为游标，null 表示最新一批）。 */
    public List<EventItem> recent(AuthPrincipal principal, Long afterId, Integer limit) {
        return recentByScope(resolveScope(principal), afterId, limit);
    }

    /**
     * 按已解析的范围拉取事件（供 SSE 长连接使用：范围在请求线程解析后传入，
     * 避免在线程池线程中读取 ThreadLocal）。
     *
     * @param scope {@code null} = 全域；空集合 = 无可见范围
     */
    public List<EventItem> recentByScope(Set<String> scope, Long afterId, Integer limit) {
        if (scope != null && scope.isEmpty()) {
            return List.of();
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<OutboxEvent> events = outboxEventMapper.selectEventsAfter(scope, afterId, size);
        return events.stream().map(this::toItem).toList();
    }

    private EventItem toItem(OutboxEvent event) {
        String payload = event.getPayload() == null ? "" : event.getPayload();
        return EventItem.builder()
                .id(event.getId())
                .tenantId(event.getTenantId())
                .eventType(event.getEventType())
                .aggregateId(event.getAggregateId())
                .summary(summarize(event))
                .status(event.getStatus())
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .payload(payload.length() > PAYLOAD_MAX_CHARS
                        ? payload.substring(0, PAYLOAD_MAX_CHARS) + "…" : payload)
                .build();
    }

    /** 由事件类型 + 载荷派生中文摘要（仅用非 PII 字段）。 */
    private String summarize(OutboxEvent event) {
        String type = event.getEventType() == null ? "" : event.getEventType();
        String label = switch (type) {
            case "ORDER_CREATED" -> "新订单创建";
            case "ORDER_PAID" -> "订单支付成功";
            case "ORDER_CANCELLED" -> "订单已取消/关闭";
            default -> type.isEmpty() ? "业务事件" : type;
        };
        String amount = null;
        if (event.getPayload() != null && !event.getPayload().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(event.getPayload());
                if (node.hasNonNull("totalAmount")) {
                    amount = node.get("totalAmount").asText();
                }
            } catch (Exception ex) { // noqa: BLE001 - 载荷解析失败不影响摘要
                log.debug("[events] payload parse failed id={}", event.getId());
            }
        }
        StringBuilder sb = new StringBuilder(label);
        if (event.getAggregateId() != null && !event.getAggregateId().isBlank()) {
            sb.append(" · ").append(event.getAggregateId());
        }
        if (amount != null) {
            sb.append(" · ¥").append(amount);
        }
        return sb.toString();
    }
}
