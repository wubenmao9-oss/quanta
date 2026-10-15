package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多腿/网格重放引擎单测：马丁整轮均价止盈、逐腿加仓（腿级→轮级聚合）、网格挂单触发、
 * 区间末收尾平仓、防未来函数、可复现。
 */
class BasketReplayEngineTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long T0 = Instant.parse("2026-09-12T23:00:00Z").getEpochSecond();
    private static final double INIT = 10000.0; // 权益 10000 → 基准手数 0.1 手（文章口径）

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

    private static BasketRuleRunner runner(int warmup, Function<BasketState, List<BasketAction>> fn) {
        return new BasketRuleRunner() {
            @Override
            public int warmupBars() {
                return warmup;
            }

            @Override
            public List<BasketAction> onBar(BasketState state) {
                return fn.apply(state);
            }
        };
    }

    private static BasketStrategy strategy(BasketRuleRunner r) {
        return new BasketStrategy() {
            @Override
            public String key() {
                return "TEST_BASKET";
            }

            @Override
            public String displayName() {
                return "测试多腿策略";
            }

            @Override
            public BasketRuleRunner newRunner() {
                return r;
            }
        };
    }

    private static BasketEngineResult run(List<KlineBar> bars, BasketRuleRunner r, int spread) {
        return run(bars, r, spread, new BasketStrategyProperties());
    }

    private static BasketEngineResult run(List<KlineBar> bars, BasketRuleRunner r, int spread,
                                          BasketStrategyProperties basketProps) {
        return new BasketReplayEngine(props(), basketProps, UTC)
                .run(bars, strategy(r), spread, 0, INIT);
    }

    /**
     * 马丁<b>倍投</b>保参：本用例专测「手数 0.1/0.2/0.4」阶梯，
     * 而全局默认已改为 {@code multiplier=1.0}（等手数），故此处显式设为 2.0。
     */
    private static BasketStrategyProperties martingaleProps() {
        BasketStrategyProperties p = new BasketStrategyProperties();
        p.getMartin().setMultiplier(2.0);
        return p;
    }

    @Test
    @DisplayName("马丁单腿：整轮均价止盈 T，腿级明细带轮号/档位/引擎类型")
    void martinSingleLegTp() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000),       // 开第 1 腿（close 2000）
                bar(T0 + 120, 2000, 2001.5, 1999.2, 2001));     // high≥2001（均价+1.0）→ 整轮止盈

        BasketRuleRunner r = runner(1, s -> s.view().cursor() == 1
                ? List.of(new BasketAction.OpenCycle(RiskSide.BUY, BasketEngineMode.MARTIN))
                : List.of());

        BasketEngineResult res = run(bars, r, 0);

        assertEquals(1, res.trades().size());
        assertEquals(1, res.cycles().size());
        ClosedTrade t = res.trades().get(0);
        assertEquals("MARTIN", t.engineKind());
        assertEquals(1, t.cycleNo());
        assertEquals(1, t.level());
        assertEquals("TP", t.exitReason());
        // (2001 − 2000) × 100 × 0.1（基准手数）= 10.0
        assertEquals(10.0, t.netProfit(), 1e-9);

        BasketEngineResult.CycleSummary c = res.cycles().get(0);
        assertEquals(1, c.cycleNo());
        assertEquals(BasketEngineMode.MARTIN, c.mode());
        assertEquals(1, c.levels());
        assertEquals(10.0, c.netProfit(), 1e-9);
        assertEquals("TP", c.closeReason());
    }

    @Test
    @DisplayName("马丁三腿：手数 0.1/0.2/0.4、整轮均价止盈；轮级聚合为 1 轮 3 腿")
    void martinThreeLegsAggregatedAsOneCycle() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000),       // 第 1 腿 @2000
                bar(T0 + 120, 1999, 1999.5, 1999.2, 1999),      // 第 2 腿 @1999
                bar(T0 + 180, 1998.5, 1999.5, 1999.2, 1998),    // 第 3 腿 @1998
                bar(T0 + 240, 1999, 2000, 1999.2, 1999.5));     // high≥1999.57 → 整轮止盈

        BasketRuleRunner r = runner(1, s -> {
            int i = s.view().cursor();
            if (s.basket() == null && i == 1) {
                return List.of(new BasketAction.OpenCycle(RiskSide.BUY, BasketEngineMode.MARTIN));
            }
            if (i == 2 || i == 3) {
                return List.of(new BasketAction.AddLeg());
            }
            return List.of();
        });

        BasketEngineResult res = run(bars, r, 0, martingaleProps());

        assertEquals(3, res.trades().size(), "逐腿各产出一条明细");
        assertEquals(1, res.cycles().size(), "三条腿归属同一轮");
        for (ClosedTrade t : res.trades()) {
            assertEquals(1, t.cycleNo());
            assertEquals("MARTIN", t.engineKind());
            assertEquals("TP", t.exitReason());
        }
        assertEquals(0.1, res.trades().get(0).volume(), 1e-9);
        assertEquals(0.2, res.trades().get(1).volume(), 1e-9);
        assertEquals(0.4, res.trades().get(2).volume(), 1e-9);

        BasketEngineResult.CycleSummary c = res.cycles().get(0);
        assertEquals(3, c.levels());
        assertEquals(0.7, c.totalVolume(), 1e-9);
        // (0.1×2000 + 0.2×1999 + 0.4×1998) / 0.7 = 1998.5714…，轮摘要按 2 位小数计（1998.57）
        assertEquals(1998.57, c.avgEntry(), 1e-9);
    }

    @Test
    @DisplayName("网格挂单：BUY_STOP 触发建 GRID 轮，逐腿独立止盈")
    void gridPendingTriggersAndLegTp() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.2, 1999.8, 2000),       // 挂 BUY_STOP@2001
                bar(T0 + 120, 2001, 2002, 2000.5, 2001.5),      // high≥2001 触发成交（entry 2001）
                bar(T0 + 180, 2002, 2002.5, 2001.5, 2002));     // high≥2002（entry+1.0）→ 腿止盈

        BasketRuleRunner r = runner(1, s -> s.view().cursor() == 1 && s.basket() == null
                ? List.of(new BasketAction.PlacePending(GridPendingOrder.Kind.BUY_STOP, 2001.0))
                : List.of());

        BasketEngineResult res = run(bars, r, 0);

        assertEquals(1, res.trades().size());
        ClosedTrade t = res.trades().get(0);
        assertEquals("GRID", t.engineKind());
        assertEquals("TP", t.exitReason());
        assertEquals(1, t.cycleNo());
        // entry=2001（BUY_STOP 跳空保护取 max(price,open)）；exit=2002 → (1.0)×100×0.01
        assertEquals(1.0, t.netProfit(), 1e-9);
        assertEquals(BasketEngineMode.GRID, res.cycles().get(0).mode());
    }

    @Test
    @DisplayName("区间末仍持仓 → 按最后收盘平仓（END），计入统计")
    void closesOpenCycleAtEnd() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000));

        BasketRuleRunner r = runner(1, s -> s.view().cursor() == 1
                ? List.of(new BasketAction.OpenCycle(RiskSide.BUY, BasketEngineMode.MARTIN))
                : List.of());

        BasketEngineResult res = run(bars, r, 0);

        assertEquals(1, res.trades().size());
        assertEquals("END", res.trades().get(0).exitReason());
        assertEquals("END", res.cycles().get(0).closeReason());
    }

    @Test
    @DisplayName("防未来函数：runner 读取未来 K 线时引擎立即抛错")
    void readingFutureBarThrows() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000));

        BasketRuleRunner peeker = runner(0, s -> {
            s.view().bar(s.view().cursor() + 1);
            return List.of();
        });

        BasketReplayEngine engine = new BasketReplayEngine(props(), new BasketStrategyProperties(), UTC);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.run(bars, strategy(peeker), 0, 0, INIT));
        assertTrue(ex.getMessage().contains("防未来函数"));
    }

    @Test
    @DisplayName("快跑：同参数两次回测结果完全一致（可复现）")
    void reproducible() {
        List<KlineBar> bars = List.of(
                bar(T0, 2000, 2000, 2000, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000),
                bar(T0 + 120, 2000, 2001.5, 1999.2, 2001));

        BasketEngineResult a = run(bars, runner(1, s -> s.view().cursor() == 1
                ? List.of(new BasketAction.OpenCycle(RiskSide.BUY, BasketEngineMode.MARTIN)) : List.of()), 0);
        BasketEngineResult b = run(bars, runner(1, s -> s.view().cursor() == 1
                ? List.of(new BasketAction.OpenCycle(RiskSide.BUY, BasketEngineMode.MARTIN)) : List.of()), 0);

        assertEquals(a.trades(), b.trades());
        assertEquals(a.finalEquity(), b.finalEquity(), 0);
    }
}
