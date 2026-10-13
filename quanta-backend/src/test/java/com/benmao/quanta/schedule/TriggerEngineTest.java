package com.benmao.quanta.schedule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.TradePlanMapper;
import com.benmao.quanta.ops.AlertService;
import com.benmao.quanta.state.SystemStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TriggerEngine 单测（M7 验收：每个触发源独立开关生效；T1/T2/T5/T6/T7 判定；T3/T4 插件受开关控制）。
 */
class TriggerEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");

    private MarketWatcher watcher;
    private Mt5BridgeService bridge;
    private TradePlanMapper tradePlanMapper;
    private SessionManager sessionManager;
    private ScheduleProperties props;
    private SystemStateService stateService;
    private AlertService alertService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        watcher = mock(MarketWatcher.class);
        bridge = mock(Mt5BridgeService.class);
        tradePlanMapper = mock(TradePlanMapper.class);
        sessionManager = mock(SessionManager.class);
        stateService = mock(SystemStateService.class);
        alertService = mock(AlertService.class);
        props = new ScheduleProperties();
        clock = new MutableClock(T0);

        when(watcher.symbol()).thenReturn("GOLD");
        when(watcher.connected()).thenReturn(true);
        when(watcher.snapshot()).thenReturn(snapshot());
        when(bridge.positions(anyString())).thenReturn(List.of());
        when(stateService.isDegraded()).thenReturn(false);
        when(sessionManager.acquire(any(), any(), any(), any())).thenReturn(new SessionManager.AcquireResult(
                SessionManager.AcquireStatus.ACQUIRED,
                new SessionManager.Lease(1, "GOLD", null, TriggerSource.T1, false), 0));

        // 默认关掉时间驱动，避免干扰单源用例
        props.getTrigger().getTime().setIntervalSeconds(0);
    }

    @Test
    @DisplayName("T1：目标价落入最新 K 线区间 → 命中并置 TRIGGERED")
    void t1FiresWhenPriceTouchesPlan() {
        TradePlan plan = plan(7L, "4200");
        when(tradePlanMapper.listPendingUnexpired(any())).thenReturn(List.of(plan));

        TriggerEngine engine = engine(List.of());
        engine.scan();

        verify(tradePlanMapper).updateStatus(7L, "TRIGGERED");
        verify(sessionManager).acquire(eq(TriggerSource.T1), eq("GOLD"), isNull(), isNull());
        assertTrue(hasHit(engine, TriggerSource.T1));
    }

    @Test
    @DisplayName("每个触发源独立开关：关掉的源绝不触发，未关的照常触发")
    void eachTriggerSwitchIsIndependent() {
        props.getTrigger().getSwitches().put("T1", false);
        props.getTrigger().getSwitches().put("T2", false);
        props.getTrigger().getSwitches().put("T5", false);
        props.getTrigger().getSwitches().put("T7", false);
        props.getTrigger().getSwitches().put("T3", true);
        when(tradePlanMapper.listPendingUnexpired(any())).thenReturn(List.of(plan(9L, "4200")));
        when(bridge.positions(anyString())).thenReturn(List.of(position(7001L, 99.0)));

        TriggerRule stub = stubRule(TriggerSource.T3, true);
        TriggerEngine engine = engine(List.of(stub));
        engine.scan();

        // T1 关：即使到价也不触发、不改状态
        verify(tradePlanMapper, never()).updateStatus(anyLong(), anyString());
        List<TriggerHit> hits = engine.recentHits();
        assertTrue(hits.stream().noneMatch(h -> h.source() == TriggerSource.T1));
        // T5 关：即使浮盈超阈值也不触发
        assertTrue(hits.stream().noneMatch(h -> h.source() == TriggerSource.T5));
        // T3 开：插件命中照常触发
        assertTrue(hits.stream().anyMatch(h -> h.source() == TriggerSource.T3));
    }

    @Test
    @DisplayName("T2：时间驱动按间隔触发一次，未到间隔不重复")
    void t2FiresOnInterval() {
        props.getTrigger().getTime().setIntervalSeconds(60);
        TriggerEngine engine = engine(List.of());

        engine.scan();
        assertEquals(1, countHits(engine, TriggerSource.T2));

        engine.scan();
        assertEquals(1, countHits(engine, TriggerSource.T2));

        clock.advance(Duration.ofSeconds(61));
        engine.scan();
        assertEquals(2, countHits(engine, TriggerSource.T2));
    }

    @Test
    @DisplayName("T5：浮盈/浮亏达阈值 → 带 ticket 命中（参与持仓级互斥）")
    void t5FiresOnProfitThreshold() {
        when(bridge.positions("GOLD")).thenReturn(List.of(position(7001L, 6.5)));

        engine(List.of()).scan();

        verify(sessionManager).acquire(eq(TriggerSource.T5), eq("GOLD"), eq(7001L), isNull());
    }

    @Test
    @DisplayName("T6：受独立开关控制，开启后人工唤醒命中")
    void t6ManualWakeGatedBySwitch() {
        props.getTrigger().getSwitches().put("T6", false);
        TriggerEngine engine = engine(List.of());
        assertThrows(BizException.class, () -> engine.manualWake("开盘前研判"));

        props.getTrigger().getSwitches().put("T6", true);
        engine.manualWake("开盘前研判");
        engine.scan();

        assertTrue(hasHit(engine, TriggerSource.T6));
    }

    @Test
    @DisplayName("T7：断线 → 恢复的跃迁即命中")
    void t7FiresOnReconnect() {
        TriggerEngine engine = engine(List.of());
        when(watcher.connected()).thenReturn(false);
        engine.scan();

        when(watcher.connected()).thenReturn(true);
        engine.scan();

        assertTrue(hasHit(engine, TriggerSource.T7));
    }

    @Test
    @DisplayName("T3/T4 插件默认关闭，打开后才参与判定")
    void pluginRulesRespectSwitch() {
        TriggerRule stub = stubRule(TriggerSource.T4, true);
        TriggerEngine engine = engine(List.of(stub));

        engine.scan();
        assertTrue(engine.recentHits().stream().noneMatch(h -> h.source() == TriggerSource.T4));

        props.getTrigger().getSwitches().put("T4", true);
        engine.scan();
        assertTrue(engine.recentHits().stream().anyMatch(h -> h.source() == TriggerSource.T4));
    }

    @Test
    @DisplayName("总开关关闭时扫描不产生任何命中")
    void disabledEngineProducesNoHit() {
        props.setEnabled(false);
        TriggerEngine engine = engine(List.of());
        when(tradePlanMapper.listPendingUnexpired(any())).thenReturn(List.of(plan(1L, "4200")));

        engine.scan();

        assertTrue(engine.recentHits().isEmpty());
        verify(tradePlanMapper, never()).listPendingUnexpired(any());
    }

    @Test
    @DisplayName("降级态（Token 超限）：扫描关闭全部触发，不产生任何命中、不改计划状态")
    void degradedClosesAllTriggers() {
        when(stateService.isDegraded()).thenReturn(true);
        when(tradePlanMapper.listPendingUnexpired(any())).thenReturn(List.of(plan(3L, "4200")));
        TriggerEngine engine = engine(List.of(stubRule(TriggerSource.T3, true)));

        engine.scan();

        assertTrue(engine.recentHits().isEmpty());
        verify(tradePlanMapper, never()).listPendingUnexpired(any());
        verify(sessionManager, never()).acquire(any(), any(), any(), any());
    }

    @Test
    @DisplayName("降级时人工唤醒（T6）也不分发")
    void degradedBlocksManualWake() {
        TriggerEngine engine = engine(List.of());
        when(stateService.isDegraded()).thenReturn(true);

        engine.manualWake("降级期间唤醒");
        engine.scan();

        assertTrue(engine.recentHits().isEmpty());
        verify(sessionManager, never()).acquire(any(), any(), any(), any());
    }

    // ==================== T7 断线/恢复告警（M13） ====================

    @Test
    @DisplayName("T7 断线/恢复：连接跃迁各触发一次关键事件告警，恢复时命中 T7")
    void t7ConnectivityAlertsOnTransitions() {
        when(watcher.connected()).thenReturn(true);
        TriggerEngine engine = engine(List.of());
        engine.scan();   // 首次建立基线（null → true），不告警

        when(watcher.connected()).thenReturn(false);
        when(watcher.consecutiveFailures()).thenReturn(3L);
        engine.scan();   // true → false：断线告警

        when(watcher.connected()).thenReturn(true);
        engine.scan();   // false → true：恢复告警 + T7 命中

        verify(alertService).connectivityDown(anyString());
        verify(alertService).connectivityUp(anyString());
        assertTrue(hasHit(engine, TriggerSource.T7));
    }

    // ==================== 辅助 ====================

    private TriggerEngine engine(List<TriggerRule> rules) {
        return new TriggerEngine(watcher, bridge, tradePlanMapper, sessionManager, props, rules,
                stateService, alertService, clock);
    }

    private boolean hasHit(TriggerEngine engine, TriggerSource source) {
        return engine.recentHits().stream().anyMatch(h -> h.source() == source);
    }

    private static long countHits(TriggerEngine engine, TriggerSource source) {
        return engine.recentHits().stream().filter(h -> h.source() == source).count();
    }

    private static TradePlan plan(long id, String target) {
        TradePlan p = new TradePlan();
        p.setId(id);
        p.setSessionId(1L);
        p.setPlanType("PRICE");
        p.setTargetPrice(new BigDecimal(target));
        p.setStatus("PENDING");
        return p;
    }

    private static Position position(long ticket, double profit) {
        return new Position(ticket, "GOLD", "buy", 0.01, 4194.0, 4194.5, 0, 0,
                profit, 0.0, 0, "", "2026-10-12 16:00");
    }

    private static TriggerRule stubRule(TriggerSource source, boolean match) {
        return new TriggerRule() {
            @Override
            public TriggerSource source() {
                return source;
            }

            @Override
            public String name() {
                return "stub-" + source;
            }

            @Override
            public boolean matches(TriggerContext ctx) {
                return match;
            }
        };
    }

    /** 默认快照：最新 K 线区间 [4190, 4210]，可覆盖 4200 目标价。 */
    private static MarketSnapshot snapshot() {
        List<KlineBar> bars = List.of(
                new KlineBar(1760000000L, 4190, 4195, 4188, 4193, 100),
                new KlineBar(1760000300L, 4193, 4210, 4190, 4200, 120));
        return new MarketSnapshot("GOLD", "M5", LocalDateTime.of(2026, 10, 12, 16, 0),
                4194.07, 4194.23, 16, 4200, 4210, 4190, bars, T0);
    }
}
