package com.yuzhuang.web;

import com.yuzhuang.common.constant.HeaderNames;
import com.yuzhuang.outbox.entity.OutboxEvent;
import com.yuzhuang.outbox.mapper.OutboxEventMapper;
import com.yuzhuang.test.WebAuthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实业务事件流契约测试（阶段 D）。
 *
 * <p>验证：数据来自 Outbox（真实事件）、按主体租户范围过滤、农户/匿名被拒绝、游标分页可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventStreamContractTest extends WebAuthTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @BeforeEach
    void clean() {
        outboxEventMapper.delete(null);
    }

    @Test
    void recentEvents_scopedByTenantAndOrderedById() throws Exception {
        OutboxEvent a1 = seedEvent(TENANT_A, "ORDER_CREATED", "ORD-EV-A1", "150.00");
        seedEvent(TENANT_B, "ORDER_CREATED", "ORD-EV-B1", "90.00");
        OutboxEvent a2 = seedEvent(TENANT_A, "ORDER_PAID", "ORD-EV-A2", "150.00");

        // 平台管理员：全域可见（含两租户）
        mockMvc.perform(get("/api/v1/events/recent")
                        .header(HeaderNames.AUTHORIZATION, bearer("PLATFORM_ADMIN", "tenant_platform_000"))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.length()").value(3));

        // 村委（TENANT_A）：只看到本租户 2 条
        mockMvc.perform(get("/api/v1/events/recent")
                        .header(HeaderNames.AUTHORIZATION, villageBearer())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data[0].aggregateId").value("ORD-EV-A1"))
                .andExpect(jsonPath("$.data[0].summary").value(org.hamcrest.Matchers.containsString("新订单创建")))
                .andExpect(jsonPath("$.data[1].eventType").value("ORDER_PAID"));

        // 游标：afterId=a1 只返回其后的事件
        mockMvc.perform(get("/api/v1/events/recent")
                        .header(HeaderNames.AUTHORIZATION, villageBearer())
                        .param("afterId", String.valueOf(a1.getId()))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(a2.getId()));
    }

    @Test
    void cooperativesSeeOnlyOwnTenantEvents() throws Exception {
        seedEvent(TENANT_A, "ORDER_CREATED", "ORD-EV-C1", "10.00");
        seedEvent(TENANT_B, "ORDER_CREATED", "ORD-EV-C2", "20.00");

        mockMvc.perform(get("/api/v1/events/recent")
                        .header(HeaderNames.AUTHORIZATION, cooperativeBearer())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].aggregateId").value("ORD-EV-C1"));
    }

    @Test
    void farmerAndAnonymousAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/events/recent").header(HeaderNames.AUTHORIZATION, farmerBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A1003"));
        mockMvc.perform(get("/api/v1/events/recent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A1002"));
        mockMvc.perform(get("/api/v1/events/stream").header(HeaderNames.AUTHORIZATION, farmerBearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void governmentWithoutScopeSeesNoEvents() throws Exception {
        seedEvent(TENANT_A, "ORDER_CREATED", "ORD-EV-G1", "30.00");
        mockMvc.perform(get("/api/v1/events/recent")
                        .header(HeaderNames.AUTHORIZATION, bearer("GOVERNMENT", TENANT_A))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private OutboxEvent seedEvent(String tenantId, String eventType, String orderNo, String amount) {
        OutboxEvent event = OutboxEvent.builder()
                .tenantId(tenantId)
                .aggregateType("ORDER")
                .aggregateId(orderNo)
                .eventType(eventType)
                .payload("{\"eventType\":\"" + eventType + "\",\"orderNo\":\"" + orderNo
                        + "\",\"totalAmount\":" + amount + "}")
                .status("PENDING")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxEventMapper.insert(event);
        return event;
    }
}
