package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重放引擎单测：单边整点差、SL/TP 盘中触发（同根悲观取 SL）、防未来函数、可复现、swap、收尾平仓。
 */
class ReplayEngineTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long T0 = Instant.parse("2026-09-12T23:00:00Z").getEpochSecond();

    private static BacktestProperties props() {
        BacktestProperties p = new BacktestProperties();
        p.setPoint(0.01);
        p.setContractSizePerLot(100.0);
        p.setVolume(0.01);
        p.setSpreadSideFactor(1.0);
        p.setSwapOn(false);
        p.setInitialEquity(500.0);
        return p;
    }

    private static KlineBar bar(long epoch, double o, double h, double l, double c) {
        return new KlineBar(epoch, o, h, l, c, 100);
    }

    /** 只在指定索引给一次开仓意图的测试策略。 */
    private static RuleRunner signalAt(int index, RiskSide side, double stopDist, double rr) {
        return new RuleRunner() {
            @Override
            public int warmupBars() {
                return 0;
            }

            @Override
            public EntrySignal onBar(ReplayView view, OpenPosition position) {
                return view.cursor() == index ? new EntrySignal(side, stopDist, rr) : null;
            }
        };
    }

    @Test
    @DisplayName("点差按单边整点差施加：买开吃 ask、卖平吃 bid，利润被点差削减")
    void spreadAppliedPerSide() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2001.5, 1999.9, 2001));

        // 零成本对照：entry=2000, tp=2001 触发 → 毛利 = 1.0×100×0.01 = 1.0
        EngineResult free = new ReplayEngine(props(), UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 1.0, 1.0), 0, 0);
        assertEquals(1, free.trades().size());
        assertEquals(1.0, free.trades().get(0).profit(), 1e-9);
        assertEquals("TP", free.trades().get(0).exitReason());

        // 16 点差：entry=2000.16（ask）、exit=2000.84（bid）→ 毛利 = 0.68
        EngineResult cost = new ReplayEngine(props(), UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 1.0, 1.0), 16, 0);
        assertEquals(0.68, cost.trades().get(0).profit(), 1e-9);
        assertTrue(cost.trades().get(0).profit() < free.trades().get(0).profit(),
                "点差必须削减利润");
    }

    @Test
    @DisplayName("同一根内 SL 与 TP 都触价时，悲观取 SL 先触发")
    void slFiresBeforeTpWhenBothTouched() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2001.5, 1998.0, 2000.5)); // high≥tp 且 low≤sl

        EngineResult result = new ReplayEngine(props(), UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 1.0, 1.0), 16, 0);

        assertEquals("SL", result.trades().get(0).exitReason());
        // exit = sl(1999) − 0.16 = 1998.84；entry = 2000.16 → 毛利 = −1.32
        assertEquals(-1.32, result.trades().get(0).profit(), 1e-9);
    }

    @Test
    @DisplayName("防未来函数：策略读取未来 K 线时引擎立即抛错")
    void readingFutureBarThrows() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2001, 1999, 2000.5));

        RuleRunner peeker = new RuleRunner() {
            @Override
            public int warmupBars() {
                return 0;
            }

            @Override
            public EntrySignal onBar(ReplayView view, OpenPosition position) {
                view.bar(view.cursor() + 1); // 试图读未来
                return null;
            }
        };

        ReplayEngine engine = new ReplayEngine(props(), UTC);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.run(bars, peeker, 0, 0));
        assertTrue(ex.getMessage().contains("防未来函数"), "异常应说明防未来函数");
    }

    @Test
    @DisplayName("同参数两次回测结果完全一致（可复现）")
    void reproducible() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2003, 1999, 2002),
                bar(T0 + 120, 2002, 2004, 2000, 2001));

        ReplayEngine engine = new ReplayEngine(props(), UTC);
        EngineResult a = engine.run(bars, signalAt(0, RiskSide.BUY, 1.0, 2.0), 16, 2);
        EngineResult b = engine.run(bars, signalAt(0, RiskSide.BUY, 1.0, 2.0), 16, 2);

        assertEquals(a.trades(), b.trades());
        assertEquals(a.finalEquity(), b.finalEquity(), 0);
        assertEquals(a.maxDrawdown(), b.maxDrawdown(), 0);
    }

    @Test
    @DisplayName("持仓跨越每日结算点则计一次 swap（固定日均近似）")
    void swapChargedAcrossSettlement() {
        BacktestProperties p = props();
        p.setSwapOn(true);
        p.setSwapPerLotPerDay(-0.5);
        p.setSwapTripleWeekday("NONE"); // 关闭三倍，便于精确断言
        p.setSettlementHour(0);

        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),                 // 2026-09-12 23:00 UTC
                bar(T0 + 2 * 3600, 2000, 2000, 2000, 2000));     // 跨过 09-13 00:00 UTC

        EngineResult result = new ReplayEngine(p, UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 100.0, 100.0), 0, 0);

        // 仅跨过 09-13 00:00 一个结算点：-0.5 × 0.01 = -0.005
        assertEquals(-0.005, result.trades().get(0).swap(), 1e-9);
        assertEquals("END", result.trades().get(0).exitReason());
    }

    @Test
    @DisplayName("区间末仍持仓时按最后收盘平仓（计入统计）")
    void closesOpenPositionAtEnd() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000.2));

        EngineResult result = new ReplayEngine(props(), UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 100.0, 100.0), 0, 0);

        assertEquals(1, result.trades().size());
        assertEquals("END", result.trades().get(0).exitReason());
    }

    @Test
    @DisplayName("空 K 线返回空结果，不抛错")
    void emptyBars() {
        EngineResult result = new ReplayEngine(props(), UTC)
                .run(List.of(), signalAt(0, RiskSide.BUY, 1.0, 1.0), 0, 0);
        assertTrue(result.trades().isEmpty());
        assertEquals(500.0, result.finalEquity(), 1e-9);
    }
}
