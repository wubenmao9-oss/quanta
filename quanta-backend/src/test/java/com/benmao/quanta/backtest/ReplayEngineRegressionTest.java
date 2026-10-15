package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单仓回测回归：P1 抽取 {@link SwapModel} 与新增 {@link ClosedTrade} 轮/腿字段后，
 * 单仓引擎行为必须与改造前完全一致。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>新增 5 参 {@code run(..., initialEquity)} 重载可覆盖初始权益，且 {@link EngineResult#initialEquity()} 回传该值；</li>
 *   <li>单仓成交明细固定 {@code engineKind="SINGLE_LEG"}、{@code cycleNo=0}、{@code level=0}；</li>
 *   <li>沿用 4 参重载（走 {@link BacktestProperties#getInitialEquity()}）与显式传同一值的 5 参结果完全一致。</li>
 * </ul>
 */
class ReplayEngineRegressionTest {

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

    /** 触发一次 BUY 止盈（零成本下净利 = 1.0）的两根 K 线。 */
    private static List<KlineBar> tpBars() {
        return List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2001.5, 1999.9, 2001));
    }

    @Test
    @DisplayName("5 参 run 覆盖初始权益：initialEquity 回传覆盖值，finalEquity = initial + 净利")
    void initialEquityOverrideTakesEffect() {
        EngineResult result = new ReplayEngine(props(), UTC)
                .run(tpBars(), signalAt(0, RiskSide.BUY, 1.0, 1.0), 0, 0, 2000.0);

        assertEquals(1, result.trades().size());
        assertEquals(2000.0, result.initialEquity(), 1e-9, "必须回传覆盖后的初始权益");
        assertEquals(1.0, result.trades().get(0).netProfit(), 1e-9);
        assertEquals(2001.0, result.finalEquity(), 1e-9, "finalEquity = initialEquity + 净利");
    }

    @Test
    @DisplayName("空 K 线时 5 参 run 也回传覆盖后的初始权益")
    void emptyBarsHonorsInitialEquity() {
        EngineResult result = new ReplayEngine(props(), UTC)
                .run(List.of(), signalAt(0, RiskSide.BUY, 1.0, 1.0), 0, 0, 777.0);

        assertEquals(777.0, result.initialEquity(), 1e-9);
        assertEquals(777.0, result.finalEquity(), 1e-9);
    }

    @Test
    @DisplayName("单仓成交明细固定 engineKind=SINGLE_LEG、cycleNo=0、level=0")
    void singleLegTradesTaggedConsistently() {
        EngineResult result = new ReplayEngine(props(), UTC)
                .run(tpBars(), signalAt(0, RiskSide.BUY, 1.0, 1.0), 16, 0);

        ClosedTrade t = result.trades().get(0);
        assertEquals("SINGLE_LEG", t.engineKind());
        assertEquals(0, t.cycleNo());
        assertEquals(0, t.level());
    }

    @Test
    @DisplayName("收尾平仓（END）同样被打上 SINGLE_LEG 标签")
    void endCloseTaggedSingleLeg() {
        List<KlineBar> bars = List.of(
                bar(T0, 1999, 2000, 1998, 2000),
                bar(T0 + 60, 2000, 2000.5, 1999.5, 2000.2));

        EngineResult result = new ReplayEngine(props(), UTC)
                .run(bars, signalAt(0, RiskSide.BUY, 100.0, 100.0), 16, 0);

        ClosedTrade t = result.trades().get(0);
        assertEquals("END", t.exitReason());
        assertEquals("SINGLE_LEG", t.engineKind());
        assertEquals(0, t.cycleNo());
        assertEquals(0, t.level());
    }

    @Test
    @DisplayName("4 参重载与显式传默认初始权益的 5 参重载结果完全一致（行为不变）")
    void fourArgMatchesFiveArgWithDefault() {
        BacktestProperties p = props();
        ReplayEngine engine = new ReplayEngine(p, UTC);

        EngineResult legacy = engine.run(tpBars(), signalAt(0, RiskSide.BUY, 1.0, 2.0), 16, 2);
        EngineResult explicit = engine.run(tpBars(), signalAt(0, RiskSide.BUY, 1.0, 2.0), 16, 2,
                p.getInitialEquity());

        assertEquals(explicit.trades(), legacy.trades());
        assertEquals(explicit.initialEquity(), legacy.initialEquity(), 0);
        assertEquals(explicit.finalEquity(), legacy.finalEquity(), 0);
        assertEquals(explicit.maxDrawdown(), legacy.maxDrawdown(), 0);
    }
}
