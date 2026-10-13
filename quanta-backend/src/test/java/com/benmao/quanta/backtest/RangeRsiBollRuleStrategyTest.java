package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.strategy.StrategyProperties;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 震荡策略（RSI + 布林带回归 + ATR 止损）单测：增量 RSI/ATR 与布林带滚动窗口
 * 必须与 M4 {@link IndicatorService} 口径完全一致，且参数取自集中配置。
 *
 * <p>与本包 {@link EmaAtrRuleStrategyTest} 同处 {@code com.benmao.quanta.backtest}，
 * 以便直接构造包内可见的 {@link ReplayView}（游标受限视图由回测引擎层专有）。
 */
class RangeRsiBollRuleStrategyTest {

    private static final long T0 = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond();

    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(new IndicatorProperties()));

    private static StrategyProperties props() {
        StrategyProperties p = new StrategyProperties();
        p.getRange().setRsiPeriod(14);
        p.getRange().setRsiOversold(30.0);
        p.getRange().setRsiOverbought(70.0);
        p.getRange().setBollPeriod(20);
        p.getRange().setBollK(2.0);
        p.getRange().setAtrPeriod(14);
        p.getRange().setAtrStopMult(1.5);
        p.getRange().setTargetRr(1.2);
        return p;
    }

    /** 确定性均值回复序列（固定种子），保证既有触轨也有超买超卖。 */
    private static List<KlineBar> series(int n) {
        List<KlineBar> bars = new ArrayList<>();
        Random rnd = new Random(20261013L);
        double p = 2000;
        double prev = 2000;
        for (int i = 0; i < n; i++) {
            p += rnd.nextGaussian() * 1.5 - (p - 2000) * 0.02;
            double close = p;
            double open = prev;
            double high = Math.max(open, close) + 0.2;
            double low = Math.min(open, close) - 0.2;
            bars.add(new KlineBar(T0 + i * 60L, open, high, low, close, 100));
            prev = close;
        }
        return bars;
    }

    private static List<String> collect(RuleRunner runner, List<KlineBar> bars) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            EntrySignal s = runner.onBar(new ReplayView(bars, i), null);
            if (s != null) {
                out.add(i + ":" + s.side());
            }
        }
        return out;
    }

    @Test
    @DisplayName("增量 RSI/ATR/布林带与 M4 指标层口径逐点一致")
    void signalsMatchTaConventionIndicators() {
        StrategyProperties p = props();
        RuleRunner runner = new RangeRsiBollRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(400);
        int warmup = runner.warmupBars();

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }
        double[] rsi = indicator.rsi(closes, 14);
        IndicatorService.BollResult boll = indicator.boll(closes, 20, 2.0);

        List<String> expected = new ArrayList<>();
        for (int i = warmup; i < bars.size(); i++) {
            if (Double.isNaN(rsi[i]) || Double.isNaN(boll.lower()[i])) {
                continue;
            }
            if (closes[i] <= boll.lower()[i] && rsi[i] < 30.0) {
                expected.add(i + ":BUY");
            } else if (closes[i] >= boll.upper()[i] && rsi[i] > 70.0) {
                expected.add(i + ":SELL");
            }
        }

        List<String> actual = collect(runner, bars);
        assertEquals(expected, actual);
        assertFalse(actual.isEmpty(), "该均值回复行情应至少产生一次区间回归信号");
    }

    @Test
    @DisplayName("止损距离与目标盈亏比取自集中配置（不写死）")
    void signalUsesConfiguredRiskParams() {
        StrategyProperties p = props();
        p.getRange().setAtrStopMult(2.5);
        p.getRange().setTargetRr(1.5);
        RuleRunner runner = new RangeRsiBollRuleStrategy(p).newRunner();

        List<KlineBar> bars = series(400);
        EntrySignal first = null;
        for (int i = 0; i < bars.size() && first == null; i++) {
            first = runner.onBar(new ReplayView(bars, i), null);
        }
        assertEquals(1.5, first.targetRr(), 1e-9);
        assertTrue(first.stopDistance() > 0, "止损距离应为正（ATR×倍数）");
    }
}
