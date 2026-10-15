package com.benmao.quanta.strategy;

import com.benmao.quanta.common.GlobalExceptionHandler;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.strategy.StrategyGateReport.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** StrategyController 单测：清单/门禁/批准的委托与回执、门禁未过 → 400。 */
class StrategyControllerTest {

    private StrategyGateService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StrategyGateService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StrategyController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static StrategyGateReport report(boolean passed) {
        LocalDateTime from = LocalDateTime.of(2026, 9, 12, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 12, 0, 0);
        Segment seg = new Segment(1L, 200, 45.0, 1.6, 60.0, 12.0, 120.0, 0.6);
        return new StrategyGateReport(5L, "趋势-EMA_ADX", "TREND_EMA_ADX", "GOLD", "M1", "TREND", 2.5,
                from, to, from.plusDays(21), 0.7, 30, 0, seg, seg, seg,
                List.of(new StrategyGateReport.Criterion("SAMPLE_SIZE", "最小交易样本量", passed, "200 笔", "≥ 100 笔", "ok")),
                passed, "样本内(A)…→样本外(B)…", passed ? "门禁全部通过" : "门禁未通过", "免责声明");
    }

    @Test
    @DisplayName("GET /api/strategy/list：幂等导入并返回两策略")
    void listReturnsCatalog() throws Exception {
        Strategy s = new Strategy();
        s.setId(1L);
        s.setName("趋势-EMA_ADX");
        s.setStatus("DRAFT");
        when(service.ensureCatalog()).thenReturn(List.of(s));

        mvc.perform(get("/api/strategy/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"));
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/gate：返回逐条判据")
    void gateReturnsReport() throws Exception {
        when(service.gate(eq(5L), isNull(), isNull(), isNull())).thenReturn(report(true));

        mvc.perform(post("/api/strategy/5/gate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleKey").value("TREND_EMA_ADX"))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.spreadPoints").value(30));
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/approve：门禁未过 → 400，回执失败原因")
    void approveRejectedWhenGateFails() throws Exception {
        when(service.approve(eq(5L), any(), any(), any()))
                .thenThrow(new BizException(ResultCode.BIZ_ERROR.code(), "门禁未通过，禁止置 ACTIVE", 400));

        mvc.perform(post("/api/strategy/5/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("门禁未通过，禁止置 ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/approve：门禁通过 → 置 ACTIVE")
    void approveSuccess() throws Exception {
        Strategy active = new Strategy();
        active.setId(5L);
        active.setStatus("ACTIVE");
        active.setApproved(true);
        when(service.approve(eq(5L), isNull(), isNull(), isNull())).thenReturn(active);

        mvc.perform(post("/api/strategy/5/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.approved").value(true));
        verify(service).approve(anyLong(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/drill-approve：演练模式未开 → 403")
    void drillApproveRejectedWhenDisabled() throws Exception {
        when(service.drillApprove(eq(17L)))
                .thenThrow(new BizException(ResultCode.BIZ_ERROR.code(), "演练模式未开启", 403));

        mvc.perform(post("/api/strategy/17/drill-approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("演练模式未开启"));
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/drill-approve：开启后置 DRILL")
    void drillApproveSuccess() throws Exception {
        Strategy drill = new Strategy();
        drill.setId(17L);
        drill.setStatus("DRILL");
        drill.setApproved(false);
        drill.setOrigin("DRILL");
        when(service.drillApprove(eq(17L))).thenReturn(drill);

        mvc.perform(post("/api/strategy/17/drill-approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRILL"))
                .andExpect(jsonPath("$.data.origin").value("DRILL"));
    }

    @Test
    @DisplayName("POST /api/strategy/{id}/drill-revoke：一键回滚为 DRAFT")
    void drillRevokeSuccess() throws Exception {
        Strategy draft = new Strategy();
        draft.setId(17L);
        draft.setStatus("DRAFT");
        when(service.drillRevoke(eq(17L))).thenReturn(draft);

        mvc.perform(post("/api/strategy/17/drill-revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }
}
