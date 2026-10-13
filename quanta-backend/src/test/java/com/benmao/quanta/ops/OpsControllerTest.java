package com.benmao.quanta.ops;

import com.benmao.quanta.common.GlobalExceptionHandler;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.reconcile.ReconcileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运维接口单测（M13）：三态切换 + 状态观测 + 非法模式 400 + 手工对账。 */
class OpsControllerTest {

    private OpsService opsService;
    private ReconcileService reconcileService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        opsService = mock(OpsService.class);
        reconcileService = mock(ReconcileService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OpsController(opsService, reconcileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/ops/state：回传运行状态快照")
    void stateReturnsSnapshot() throws Exception {
        when(opsService.state()).thenReturn(new OpsService.OpsState("PAUSED", true, 123L, null));

        mvc.perform(get("/api/ops/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runMode").value("PAUSED"))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.tokenUsedToday").value(123));
    }

    @Test
    @DisplayName("POST /api/ops/mode：切换到 TAKEOVER 并回传新状态")
    void modeSwitchPassesThrough() throws Exception {
        when(opsService.state()).thenReturn(new OpsService.OpsState("TAKEOVER", false, 0L, null));

        mvc.perform(post("/api/ops/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TAKEOVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runMode").value("TAKEOVER"));

        verify(opsService).setMode("TAKEOVER");
    }

    @Test
    @DisplayName("POST /api/ops/mode：非法模式 → 400 且回执合法取值")
    void modeInvalidRejected() throws Exception {
        doThrow(new BizException(ResultCode.PARAM_ERROR.code(),
                "非法运行模式：BOGUS，可选 AUTO / PAUSED / TAKEOVER", 400))
                .when(opsService).setMode("BOGUS");

        mvc.perform(post("/api/ops/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("AUTO / PAUSED / TAKEOVER")));
    }

    @Test
    @DisplayName("POST /api/ops/reconcile：立即对账并回传报告")
    void reconcileReturnsReport() throws Exception {
        when(reconcileService.reconcile())
                .thenReturn(new ReconcileService.ReconcileReport(null, null, 3, List.of()));

        mvc.perform(post("/api/ops/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mt5DealCount").value(3))
                .andExpect(jsonPath("$.data.diffs").isArray());
    }
}
