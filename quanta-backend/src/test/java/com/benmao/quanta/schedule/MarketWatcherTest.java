package com.benmao.quanta.schedule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.mapper.MarketKlineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarketWatcher 单测（M7 验收：盯盘产出快照且不耗 token / 断线退避自动重连 / 落库失败不影响盯盘）。
 */
class MarketWatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");

    private Mt5BridgeService bridge;
    private MarketKlineMapper klineMapper;
    private ScheduleProperties props;
    private MutableClock clock;
    private MarketWatcher watcher;

    @BeforeEach
    void setUp() {
        bridge = mock(Mt5BridgeService.class);
        klineMapper = mock(MarketKlineMapper.class);
        props = new ScheduleProperties();
        props.getMarketWatcher().setPersistKlines(false);
        clock = new MutableClock(T0);
        watcher = new MarketWatcher(bridge, klineMapper, props, clock);
    }

    @Test
    @DisplayName("盯盘成功：产出行情快照（纯数据，不涉及任何模型调用）")
    void pollProducesSnapshot() {
        when(bridge.klines(eq("GOLD"), eq("M5"), anyInt())).thenReturn(bars());
        when(bridge.quote("GOLD")).thenReturn(new Quote("GOLD", 4194.07, 4194.23, 16, 2, 1760000000L));

        watcher.poll();

        assertTrue(watcher.connected());
        MarketSnapshot snap = watcher.snapshot();
        assertNotNull(snap);
        assertEquals("GOLD", snap.symbol());
        assertEquals("M5", snap.timeframe());
        assertEquals(4194.07, snap.bid(), 1e-9);
        assertEquals(4194.23, snap.ask(), 1e-9);
        assertEquals(16, snap.spreadPoints());
        assertEquals(4200.0, snap.last(), 1e-9);
        assertEquals(4202.0, snap.high(), 1e-9);
        assertEquals(4194.0, snap.low(), 1e-9);
        assertEquals(3, snap.bars().size());
    }

    @Test
    @DisplayName("取报价失败时用收盘价兜底，仍算盯盘成功")
    void quoteFailureFallsBackToClose() {
        when(bridge.klines(anyString(), anyString(), anyInt())).thenReturn(bars());
        when(bridge.quote(anyString())).thenThrow(new RuntimeException("quote down"));

        watcher.poll();

        assertTrue(watcher.connected());
        assertEquals(4200.0, watcher.snapshot().bid(), 1e-9);
        assertEquals(0, watcher.snapshot().spreadPoints());
    }

    @Test
    @DisplayName("空 K 线视为一次失败，标记断开")
    void emptyKlinesIsFailure() {
        when(bridge.klines(anyString(), anyString(), anyInt())).thenReturn(List.of());

        watcher.poll();

        assertFalse(watcher.connected());
        assertEquals(1, watcher.consecutiveFailures());
    }

    @Test
    @DisplayName("断线后按退避重试，桥接恢复即自动重连")
    void autoReconnectWithBackoff() {
        when(bridge.klines(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("连接中断"));

        watcher.poll();
        assertFalse(watcher.connected());
        assertEquals(1, watcher.consecutiveFailures());

        // 退避窗口内再次轮询：直接跳过，不再打扰桥接
        watcher.poll();
        verify(bridge, times(1)).klines(anyString(), anyString(), anyInt());

        // 推进时钟越过退避窗口，桥接恢复（用 doReturn 重设，避免 when() 触发当前的抛异常桩）
        clock.advance(Duration.ofMillis(props.getMarketWatcher().getReconnectBaseMs() + 1));
        doReturn(bars()).when(bridge).klines(anyString(), anyString(), anyInt());

        watcher.poll();

        assertTrue(watcher.connected());
        assertEquals(0, watcher.consecutiveFailures());
        assertNotNull(watcher.snapshot());
    }

    @Test
    @DisplayName("连续失败达阈值后主动重登桥接（自动重连）")
    void attemptsReloginAfterRepeatedFailures() {
        when(bridge.klines(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("连接中断"));

        watcher.poll();
        clock.advance(Duration.ofMillis(props.getMarketWatcher().getReconnectBaseMs() + 1));
        watcher.poll();

        verify(bridge, atLeastOnce()).connect();
    }

    @Test
    @DisplayName("K 线落库失败被吞掉，不影响盯盘")
    void persistFailureDoesNotBreakWatching() {
        props.getMarketWatcher().setPersistKlines(true);
        when(bridge.klines(anyString(), anyString(), anyInt())).thenReturn(bars());
        when(bridge.quote(anyString())).thenReturn(new Quote("GOLD", 4194.07, 4194.23, 16, 2, 1L));
        when(klineMapper.insertIgnore(anyString(), anyString(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        watcher.poll();

        assertTrue(watcher.connected());
        assertNotNull(watcher.snapshot());
        verify(klineMapper, atLeastOnce()).insertIgnore(anyString(), anyString(), any(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    /** 三根升序 K 线；末根收 4200、高 4202、低 4194。 */
    private static List<KlineBar> bars() {
        return List.of(
                new KlineBar(1760000000L, 4190, 4195, 4188, 4193, 100),
                new KlineBar(1760000300L, 4193, 4198, 4191, 4196, 120),
                new KlineBar(1760000600L, 4196, 4202, 4194, 4200, 150));
    }
}
