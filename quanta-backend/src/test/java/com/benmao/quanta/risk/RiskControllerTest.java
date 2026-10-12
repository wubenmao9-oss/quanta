package com.benmao.quanta.risk;

import com.benmao.quanta.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 风控接口单测（M5）：放行/拒绝回执 + 阈值回显 + 非法入参。 */
class RiskControllerTest {

    private RiskGuard guard;
    private RiskConfigService riskConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        guard = mock(RiskGuard.class);
        riskConfig = mock(RiskConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RiskController(guard, riskConfig))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /evaluate：放行时返回 allowed=true 与已跑规则清单")
    void evaluateAllowed() throws Exception {
        when(guard.evaluate(any())).thenReturn(new RiskDecision(RiskAction.OPEN, true, List.of(),
                List.of("R1", "R2", "R5", "R11")));

        mvc.perform(post("/api/risk/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"OPEN\",\"symbol\":\"GOLD\",\"side\":\"BUY\","
                                + "\"volume\":0.01,\"sl\":4189.23}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("OPEN"))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.checked.length()").value(4));
    }

    @Test
    @DisplayName("POST /evaluate：被拒时返回结构化原因 + 合法边界值")
    void evaluateRejected() throws Exception {
        RiskViolation v = RiskViolation.of("R5", "SL_TOO_CLOSE", "SL 贴价",
                Map.of("minSlPrice", 4193.43), "把 SL 移到 minSlPrice 之外");
        when(guard.evaluate(any())).thenReturn(new RiskDecision(RiskAction.OPEN, false, List.of(v),
                List.of("R1", "R5")));

        mvc.perform(post("/api/risk/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"OPEN\",\"symbol\":\"GOLD\",\"side\":\"BUY\","
                                + "\"volume\":0.01,\"sl\":4193.73}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.violations[0].rule").value("R5"))
                .andExpect(jsonPath("$.data.violations[0].code").value("SL_TOO_CLOSE"))
                .andExpect(jsonPath("$.data.violations[0].bounds.minSlPrice").value(4193.43));
    }

    @Test
    @DisplayName("POST /evaluate：非法动作被拒并回执可选值（400）")
    void invalidActionRejected() throws Exception {
        mvc.perform(post("/api/risk/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"HEDGE\",\"symbol\":\"GOLD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OPEN, CLOSE, MODIFY")));
    }

    @Test
    @DisplayName("GET /thresholds：回显生效阈值与运行模式")
    void thresholdsEcho() throws Exception {
        RiskThresholds th = new RiskThresholds(new BigDecimal("0.01"), new BigDecimal("0.01"), 3,
                new BigDecimal("50"), new BigDecimal("2.00"), new BigDecimal("1.00"),
                new BigDecimal("5.00"), 3, 0, new BigDecimal("50"), List.of("GOLD"));
        when(riskConfig.thresholds()).thenReturn(th);
        when(riskConfig.runMode()).thenReturn("AUTO");

        mvc.perform(get("/api/risk/thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thresholds.maxVolume").value(0.01))
                .andExpect(jsonPath("$.data.thresholds.minRr").value(1.00))
                .andExpect(jsonPath("$.data.thresholds.symbolWhitelist[0]").value("GOLD"))
                .andExpect(jsonPath("$.data.runMode").value("AUTO"));
        verify(riskConfig).thresholds();
    }
}
