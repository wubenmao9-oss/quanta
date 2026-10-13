package com.benmao.quanta.agent;

import com.benmao.quanta.schedule.TriggerHit;
import com.benmao.quanta.schedule.TriggerSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentTriggerWakeHandler 单测（M14）：触发命中 → AgentDecideRequest 映射正确；异常不外抛。
 */
class AgentTriggerWakeHandlerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final AgentSessionService sessionService = mock(AgentSessionService.class);
    private final AgentTriggerWakeHandler handler = new AgentTriggerWakeHandler(agentService, sessionService);

    @Test
    @DisplayName("触发命中映射为 AgentDecideRequest：triggerType=源名、reason=命中原因、symbol=命中品种")
    void mapsTriggerHitToDecideRequest() {
        when(agentService.decide(any())).thenReturn(result());
        TriggerHit hit = new TriggerHit(TriggerSource.T1, "GOLD",
                "价格触及计划目标 4200", 7L, null, null, Instant.now());

        handler.onTrigger(hit);

        ArgumentCaptor<AgentDecideRequest> captor = ArgumentCaptor.forClass(AgentDecideRequest.class);
        verify(agentService).decide(captor.capture());
        AgentDecideRequest req = captor.getValue();
        assertEquals("GOLD", req.symbol());
        assertEquals("T1", req.triggerType());
        assertEquals("价格触及计划目标 4200", req.reason());
    }

    @Test
    @DisplayName("空命中不触发研判")
    void nullHitIgnored() {
        assertDoesNotThrow(() -> handler.onTrigger(null));
    }

    @Test
    @DisplayName("AgentService 抛异常被吞掉，不影响调度线程")
    void swallowsAgentException() {
        when(agentService.decide(any())).thenThrow(new RuntimeException("LLM 不可用"));
        TriggerHit hit = new TriggerHit(TriggerSource.T5, "GOLD", "浮盈达阈值", null, 7001L, null, Instant.now());

        assertDoesNotThrow(() -> handler.onTrigger(hit));
    }

    @Test
    @DisplayName("研判结束后关闭自动唤醒会话（短生命周期研判窗口，避免 ACTIVE 累积）")
    void closesSessionAfterDecision() {
        when(agentService.decide(any())).thenReturn(result());
        TriggerHit hit = new TriggerHit(TriggerSource.T2, "GOLD", "时间驱动", null, null, null, Instant.now());

        handler.onTrigger(hit);

        verify(sessionService).close(1L);
    }

    @Test
    @DisplayName("研判失败（无会话 id）不调用关闭，且异常不外抛")
    void doesNotCloseWhenNoSession() {
        when(agentService.decide(any())).thenThrow(new RuntimeException("LLM 不可用"));
        TriggerHit hit = new TriggerHit(TriggerSource.T2, "GOLD", "时间驱动", null, null, null, Instant.now());

        handler.onTrigger(hit);

        verify(sessionService, never()).close(any());
    }

    private static AgentDecideResult result() {
        return new AgentDecideResult(1L, 1L, "trace", "T1", 1L, "趋势", 1, "deepseek-chat",
                AgentAction.WAIT, AgentAction.WAIT, 0.8, false, "观察", Map.of(),
                false, null, null, 10, 100, null);
    }
}
