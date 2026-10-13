package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.strategy.StrategyProperties;
import com.benmao.quanta.strategy.rule.TrendEmaAdxRuleStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趋势策略（EMA 金叉死叉 + ADX 过滤 + ATR 止损）单测：
 * 增量实现必须与 M4 {@link IndicatorService}（TA-Lib 约定、已对 numpy 逐点校验）口径完全一致，
 * 且 ADX 过滤与参数取自集中配置。
 *
 * <p>与本包 {@link EmaAtrRuleStrategyTest} 同处 {@code com.benmao.quanta.backtest}，
 * 以便直接构造包内可见的 {@link ReplayView}（游标受限视图由回测引擎层专有）。
 */
class TrendEmaAdxRuleStrategyTest {

    private static final long T0 = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond();

    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(new IndicatorProperties()));

    private static StrategyProperties props() {
        StrategyProperties p = new StrategyProperties();
        p.getTrend().setEmaFast(5);
        p.getTrend().setEmaSlow(10);
        p.getTrend().setAdxPeriod(5);
        p.getTrend().setAdxThreshold(0.0); // 关闭过滤，先验口径一致性
        p.getTrend().setAtrPeriod(5);
        p.getTrend().setAtrStopMult(1.5);
        p.getTrend().setTargetRr(2.0);
        return p;
    }

    /** 先震荡、后强趋势的确定性行情（制造穿越与 ADX 抬升）。 */
    private static List<KlineBar> series(int n) {
        List<KlineBar> bars = new ArrayList<>();
        double prev = 2000;
        for (int i = 0; i < n; i++) {
            double c;
            if (i < n / 3) {
                c = 2000 + Math.sin(i * 0.5) * 1.5;
            } else if (i < 2 * n / 3) {
                c = prev + 0.8;
            } else {
                c = prev - 0.8;
            }
            double open = prev;
            double high = Math.max(open, c) + 0.3;
            double low = Math.min(open, c) - 0.3;
            bars.add(new KlineBar(T0 + i * 60L, open, high, low, c, 100));
            prev = c;
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
    @DisplayName("增量 EMA/ADX/ATR 与 M4 指标层口径逐点一致（过滤关闭）")
    void signalsMatchTaConventionIndicators() {
        StrategyProperties p = props();
        RuleRunner runner = new TrendEmaAdxRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(150);
        int warmup = runner.warmupBars();

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }
        double[] fast = indicator.ema(closes, 5);
        double[] slow = indicator.ema(closes, 10);
        double[] adx = indicator.adx(bars, 5).adx();

        List<String> expected = new ArrayList<>();
        for (int i = warmup; i < bars.size(); i++) {
            if (Double.isNaN(fast[i]) || Double.isNaN(slow[i]) || Double.isNaN(fast[i - 1])
                    || Double.isNaN(slow[i - 1]) || Double.isNaN(adx[i])) {
                continue;
            }
            if (fast[i - 1] <= slow[i - 1] && fast[i] > slow[i]) {
                expected.add(i + ":BUY");
            } else if (fast[i - 1] >= slow[i - 1] && fast[i] < slow[i]) {
                expected.add(i + ":SELL");
            }
        }

        List<String> actual = collect(runner, bars);
        assertEquals(expected, actual);
        assertFalse(actual.isEmpty(), "该行情应至少产生一次均线穿越信号");
    }

    @Test
    @DisplayName("ADX 过滤生效：阈值拉高到 100 → 不再出信号")
    void adxFilterSuppressesSignals() {
        StrategyProperties p = props();
        List<KlineBar> bars = series(150);
        assertFalse(collect(new TrendEmaAdxRuleStrategy(p).newRunner(), bars).isEmpty(),
                "阈值 0 时应出信号");

        p.getTrend().setAdxThreshold(100.0);
        assertTrue(collect(new TrendEmaAdxRuleStrategy(p).newRunner(), bars).isEmpty(),
                "阈值 100（ADX 不可能达到）应全部被过滤");
    }

    @Test
    @DisplayName("止损距离与目标盈亏比取自集中配置（不写死）")
    void signalUsesConfiguredRiskParams() {
        StrategyProperties p = props();
        p.getTrend().setAdxThreshold(0.0);
        p.getTrend().setAtrStopMult(2.0);
        p.getTrend().setTargetRr(3.0);
        RuleRunner runner = new TrendEmaAdxRuleStrategy(p).newRunner();

        List<KlineBar> bars = series(150);
        EntrySignal first = null;
        for (int i = 0; i < bars.size() && first == null; i++) {
            first = runner.onBar(new ReplayView(bars, i), null);
        }
        assertEquals(3.0, first.targetRr(), 1e-9);
        assertTrue(first.stopDistance() > 0, "止损距离应为正（ATR×倍数）");
    }
}
