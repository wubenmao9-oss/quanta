package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.strategy.StrategyProperties;
import com.benmao.quanta.strategy.rule.TinyScalpRuleStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演练高频薅点策略（TINY_SCALP）单测：固定美元止损、<b>空仓即出信号（一次一笔、频率最大化）</b>、
 * 方向随 EMA 排列反转，且 EMA 口径与 M4 {@link IndicatorService}（已对 numpy 逐点校验）一致。
 *
 * <p>与本包 {@link TrendEmaAdxRuleStrategyTest} 同处 {@code com.benmao.quanta.backtest}，
 * 以便直接构造包内可见的 {@link ReplayView}（游标受限视图由回测引擎层专有）。
 */
class TinyScalpRuleStrategyTest {

    private static final long T0 = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond();

    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(new IndicatorProperties()));

    private static StrategyProperties props() {
        StrategyProperties p = new StrategyProperties();
        p.getTiny().setEmaFast(3);
        p.getTiny().setEmaSlow(5);
        p.getTiny().setSlDistanceUsd(2.5);
        p.getTiny().setTargetRr(1.2);
        return p;
    }

    /** 前段上行、后段下行的确定性行情（制造多头/空头排列两段）。 */
    private static List<KlineBar> series(int n) {
        List<KlineBar> bars = new ArrayList<>();
        double prev = 2000;
        for (int i = 0; i < n; i++) {
            double c = (i < n / 2) ? prev + 0.5 : prev - 0.5;
            double open = prev;
            double high = Math.max(open, c) + 0.2;
            double low = Math.min(open, c) - 0.2;
            bars.add(new KlineBar(T0 + i * 60L, open, high, low, c, 100));
            prev = c;
        }
        return bars;
    }

    @Test
    @DisplayName("空仓时预热后每根 K 线都出信号（频率最大化），止损/止盈固定取自配置")
    void opensOnEveryBarWhenFlat() {
        StrategyProperties p = props();
        RuleRunner runner = new TinyScalpRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(60);
        int warmup = runner.warmupBars();

        int signals = 0;
        for (int i = 0; i < bars.size(); i++) {
            EntrySignal s = runner.onBar(new ReplayView(bars, i), null);
            if (i < warmup) {
                assertNull(s, "预热期不应出信号：i=" + i);
                continue;
            }
            assertNotNull(s, "预热后空仓每根都必须出信号：i=" + i);
            assertEquals(2.5, s.stopDistance(), 1e-9, "固定止损距离应为 2.5 美元");
            assertEquals(1.2, s.targetRr(), 1e-9, "目标盈亏比应为 1.2");
            signals++;
        }
        assertEquals(bars.size() - warmup, signals, "除预热期外每根都应出信号");
    }

    @Test
    @DisplayName("已有持仓 → 返回 null（一次仅一笔）")
    void noSignalWhileHoldingPosition() {
        StrategyProperties p = props();
        RuleRunner runner = new TinyScalpRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(30);
        OpenPosition held = new OpenPosition(RiskSide.BUY, 5, 2010.0, 2007.5, 2013.0);

        for (int i = 0; i < bars.size(); i++) {
            assertNull(runner.onBar(new ReplayView(bars, i), held), "持仓中不得再出信号：i=" + i);
        }
    }

    @Test
    @DisplayName("方向随 EMA 排列反转，且与 M4 指标层 EMA 口径一致")
    void sideFollowsEmaOrderAndMatchesIndicatorLayer() {
        StrategyProperties p = props();
        RuleRunner runner = new TinyScalpRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(60);

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }
        double[] fast = indicator.ema(closes, 3);
        double[] slow = indicator.ema(closes, 5);

        boolean sawBuy = false;
        boolean sawSell = false;
        for (int i = 0; i < bars.size(); i++) {
            EntrySignal s = runner.onBar(new ReplayView(bars, i), null);
            if (s == null || fast[i] == slow[i]) {
                continue;
            }
            RiskSide expected = fast[i] > slow[i] ? RiskSide.BUY : RiskSide.SELL;
            assertEquals(expected, s.side(), "方向应与 EMA 快慢排列一致：i=" + i);
            sawBuy |= s.side() == RiskSide.BUY;
            sawSell |= s.side() == RiskSide.SELL;
        }
        assertTrue(sawBuy && sawSell, "该行情上行/下行两段应分别产出 BUY 与 SELL");
    }

    @Test
    @DisplayName("止损距离/盈亏比取自集中配置（不写死）")
    void usesConfiguredRiskParams() {
        StrategyProperties p = props();
        p.getTiny().setSlDistanceUsd(3.5);
        p.getTiny().setTargetRr(2.0);
        RuleRunner runner = new TinyScalpRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(30);

        EntrySignal first = null;
        for (int i = 0; i < bars.size() && first == null; i++) {
            first = runner.onBar(new ReplayView(bars, i), null);
        }
        assertNotNull(first);
        assertEquals(3.5, first.stopDistance(), 1e-9);
        assertEquals(2.0, first.targetRr(), 1e-9);
    }
}
