package com.benmao.quanta.agent;

import com.benmao.quanta.common.GlobalExceptionHandler;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent 接口单测（M10）：/api/agent/decide 透传、/decisions 列表、/tools 契约清单、异常状态码。 */
class AgentControllerTest {

    private AgentService agentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(agentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/agent/decide：回执契约动作与执行结果")
    void decidePassesThrough() throws Exception {
        when(agentService.decide(any())).thenReturn(result());

        mvc.perform(post("/api/agent/decide").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"timeframe\":\"M5\",\"reason\":\"人工唤醒\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.action").value("WAIT"))
                .andExpect(jsonPath("$.data.modelAction").value("OPEN"))
                .andExpect(jsonPath("$.data.downgraded").value(true))
                .andExpect(jsonPath("$.data.executed").value(false));
    }

    @Test
    @DisplayName("POST /api/agent/decide：空请求体也走默认值")
    void decideWithEmptyBody() throws Exception {
        when(agentService.decide(any())).thenReturn(result());

        mvc.perform(post("/api/agent/decide").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("POST /api/agent/decide：中枢关闭 → 409 且透传原因")
    void decideRejected() throws Exception {
        when(agentService.decide(any())).thenThrow(new BizException(
                com.benmao.quanta.common.ResultCode.BIZ_ERROR.code(),
                "Agent 中枢已关闭（quanta.agent.enabled=false），拒绝决策请求", 409));

        mvc.perform(post("/api/agent/decide").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("中枢已关闭")));
    }

    @Test
    @DisplayName("GET /api/agent/decisions：返回最近决策日志")
    void listsDecisions() throws Exception {
        AgentDecisionLog log = new AgentDecisionLog();
        log.setId(1L);
        log.setAction("WAIT");
        log.setTokens(30);
        when(agentService.listDecisions(anyInt())).thenReturn(List.of(log));

        mvc.perform(get("/api/agent/decisions").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("WAIT"))
                .andExpect(jsonPath("$.data[0].tokens").value(30));
    }

    @Test
    @DisplayName("GET /api/agent/tools：声明写工具不暴露给大模型")
    void showsTools() throws Exception {
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("actions", "OPEN, CLOSE, MODIFY, PLAN, WAIT, NOOP");
        tools.put("confidenceThreshold", 0.6);
        tools.put("writeToolsExposedToLlm", false);
        when(agentService.describeTools()).thenReturn(tools);

        mvc.perform(get("/api/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.writeToolsExposedToLlm").value(false))
                .andExpect(jsonPath("$.data.actions", containsString("NOOP")));
    }

    private static AgentDecideResult result() {
        return new AgentDecideResult(999L, 7L, "tr-1", "T6", 13L, "趋势-EMA_ADX", 1, "deepseek-chat",
                AgentAction.WAIT, AgentAction.OPEN, 0.3, true, "依据不足", Map.of("side", "BUY"),
                false, null, null, 30, 120, null);
    }
}
