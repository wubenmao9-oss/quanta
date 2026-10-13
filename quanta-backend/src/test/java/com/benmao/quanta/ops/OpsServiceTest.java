package com.benmao.quanta.ops;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.schedule.TriggerEngine;
import com.benmao.quanta.state.RunMode;
import com.benmao.quanta.state.SystemStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运维服务单测（M13）：三态切换落库 + 审计 + 通知会话 + 状态观测 + 非法模式拒绝。
 */
class OpsServiceTest {

    private SystemStateService stateService;
    private OperationAuditService auditService;
    private TriggerEngine triggerEngine;
    private OpsProperties props;
    private OpsService service;

    @BeforeEach
    void setUp() {
        stateService = mock(SystemStateService.class);
        auditService = mock(OperationAuditService.class);
        triggerEngine = mock(TriggerEngine.class);
        props = new OpsProperties();
        service = new OpsService(stateService, auditService, triggerEngine, props);
    }

    @Test
    @DisplayName("state()：透传运行模式/降级态/当日 Token 用量")
    void stateSnapshot() {
        when(stateService.runMode()).thenReturn(RunMode.PAUSED);
        when(stateService.isDegraded()).thenReturn(true);
        when(stateService.tokenUsedToday()).thenReturn(123L);
        when(stateService.tokenDate()).thenReturn(LocalDate.of(2026, 10, 13));

        OpsService.OpsState s = service.state();

        assertEquals("PAUSED", s.runMode());
        assertTrue(s.degraded());
        assertEquals(123L, s.tokenUsedToday());
        assertEquals(LocalDate.of(2026, 10, 13), s.tokenDate());
    }

    @Test
    @DisplayName("setMode：变化时落库 + 写 MODE_* 审计 + 通知会话，返回 true")
    void setModeChangedAuditsAndNotifies() {
        when(stateService.runMode()).thenReturn(RunMode.AUTO);
        when(stateService.setRunMode("TAKEOVER")).thenReturn(true);

        assertTrue(service.setMode("takeover"));

        verify(stateService).setRunMode("TAKEOVER");
        verify(auditService).write("MANUAL", "MODE_TAKEOVER", "sys_state.run_mode", "AUTO", "TAKEOVER", null);
        verify(triggerEngine).systemEvent(anyString(), anyString());
    }

    @Test
    @DisplayName("setMode：同模式幂等（落库返回 false）→ 仍审计但不通知会话")
    void setModeIdempotentNoNotify() {
        when(stateService.runMode()).thenReturn(RunMode.TAKEOVER);
        when(stateService.setRunMode("TAKEOVER")).thenReturn(false);

        assertFalse(service.setMode("TAKEOVER"));

        verify(auditService).write("MANUAL", "MODE_TAKEOVER", "sys_state.run_mode", "TAKEOVER", "TAKEOVER", null);
        verify(triggerEngine, never()).systemEvent(anyString(), anyString());
    }

    @Test
    @DisplayName("setMode：非法模式抛参数异常且不落库")
    void setModeInvalidRejected() {
        when(stateService.runMode()).thenReturn(RunMode.AUTO);

        BizException e = assertThrows(BizException.class, () -> service.setMode("BOGUS"));
        assertTrue(e.getMessage().contains("AUTO / PAUSED / TAKEOVER"), e.getMessage());
        verify(stateService, never()).setRunMode(anyString());
    }
}
