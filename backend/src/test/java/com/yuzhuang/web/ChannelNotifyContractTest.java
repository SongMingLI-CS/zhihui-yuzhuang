package com.yuzhuang.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 渠道回调端点契约测试（阶段 G）：默认未配置时必须明确拒绝，不得假装受理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelNotifyContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unconfiguredChannel_returns503C5003() throws Exception {
        mockMvc.perform(post("/api/v1/channels/douyin/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"DY-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("C5003"));
    }

    @Test
    void unknownChannel_returns404A1004() throws Exception {
        mockMvc.perform(post("/api/v1/channels/pinduoduo/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A1004"));
    }

    @Test
    void kuaishouAndB2bAreAlsoDisabledByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/channels/kuaishou/notify")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("C5003"));
        mockMvc.perform(post("/api/v1/channels/b2b_portal/notify")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("C5003"));
    }
}
