package com.benmao.quanta.schedule;

import com.benmao.quanta.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleController 单测：状态总览与人工唤醒（T6）路由。
 */
class ScheduleControllerTest {

    private TriggerEngine triggerEngine;
    private SessionManager sessionManager;
    private MarketWatcher marketWatcher;
    private ScheduleProperties props;
    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        triggerEngine = mock(TriggerEngine.class);
        sessionManager = mock(SessionManager.class);
        marketWatcher = mock(MarketWatcher.class);
        props = new ScheduleProperties();
        controller = new ScheduleController(triggerEngine, sessionManager, marketWatcher, props);

        when(sessionManager.maxSession()).thenReturn(5);
        when(sessionManager.activeCount()).thenReturn(2);
        when(sessionManager.queuedCount()).thenReturn(1);
        when(marketWatcher.connected()).thenReturn(true);
        when(marketWatcher.symbol()).thenReturn("GOLD");
        when(marketWatcher.timeframe()).thenReturn("M5");
        when(marketWatcher.consecutiveFailures()).thenReturn(0L);
        when(marketWatcher.snapshot()).thenReturn(new MarketSnapshot("GOLD", "M5",
                LocalDateTime.of(2026, 10, 12, 16, 0), 4194.07, 4194.23, 16, 4200, 4202, 4194,
                List.of(), Instant.parse("2026-10-12T08:00:00Z")));
        when(triggerEngine.recentHits()).thenReturn(List.of(new TriggerHit(TriggerSource.T1, "GOLD",
                "到预期位", 1L, null, LocalDateTime.of(2026, 10, 12, 16, 0),
                Instant.parse("2026-10-12T08:00:00Z"))));
    }

    @Test
    @DisplayName("状态总览：暴露盯盘状态、触发开关、会话窗口与最近命中")
    void statusExposesState() {
        Result<ScheduleController.StatusView> result = controller.status();

        ScheduleController.StatusView view = result.getData();
        assertTrue(view.enabled());
        assertEquals(5, view.maxSession());
        assertEquals(2, view.activeSessions());
        assertEquals(1, view.queued());
        assertEquals(8, view.switches().size());
        assertTrue(view.switches().get("T1"));
        assertFalse(view.switches().get("T3"));
        assertTrue(view.marketWatcher().connected());
        assertEquals(1, view.recentHits().size());
    }

    @Test
    @DisplayName("人工唤醒：转发到 TriggerEngine")
    void wakeDelegates() {
        Result<?> result = controller.wake(new ScheduleController.WakeReq("开盘前研判"));

        verify(triggerEngine).manualWake("开盘前研判");
        assertEquals(0, result.getCode());
    }
}
