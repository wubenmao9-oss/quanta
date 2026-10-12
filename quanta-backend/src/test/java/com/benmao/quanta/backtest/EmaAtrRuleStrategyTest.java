package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.backtest.rule.EmaAtrRuleStrategy;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * EMA + ATR 规则策略单测：增量实现必须与 M4 {@link IndicatorService}（TA-Lib 约定、已对 numpy 逐点校验）
 * 的金叉/死叉口径完全一致，且只经 {@link ReplayView} 读取当前及之前数据。
 */
class EmaAtrRuleStrategyTest {

    private static final long T0 = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond();

    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(new IndicatorProperties()));

    private static BacktestProperties weightedProps() {
        BacktestProperties props = new BacktestProperties();
        props.getStrategy().setEmaFast(5);
        props.getStrategy().setEmaSlow(10);
        props.getStrategy().setAtrPeriod(5);
        props.getStrategy().setAtrStopMult(1.5);
        props.getStrategy().setTargetRr(2.0);
        return props;
    }

    /** 先跌后涨的行情（制造金叉）。 */
    private static List<KlineBar> trendReversal(int n) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = i < n / 2 ? 2000 - i : 2000 - (n / 2) + (i - n / 2);
            long t = T0 + i * 60L;
            bars.add(new KlineBar(t, c, c + 0.2, c - 0.2, c, 100));
        }
        return bars;
    }

    @Test
    @DisplayName("增量金叉/死叉与 M4 指标层口径逐点一致")
    void signalsMatchTaConventionIndicators() {
        BacktestProperties props = weightedProps();
        EmaAtrRuleStrategy strategy = new EmaAtrRuleStrategy(props);
        List<KlineBar> bars = trendReversal(80);

        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }
        double[] fast = indicator.ema(closes, 5);
        double[] slow = indicator.ema(closes, 10);

        RuleRunner runner = strategy.newRunner();
        int warmup = runner.warmupBars();

        List<String> actual = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            ReplayView view = new ReplayView(bars, i);
            EntrySignal signal = runner.onBar(view, null);
            if (signal != null) {
                actual.add(i + ":" + signal.side());
            }
        }

        List<String> expected = new ArrayList<>();
        for (int i = warmup; i < bars.size(); i++) {
            if (Double.isNaN(fast[i - 1]) || Double.isNaN(slow[i - 1])
                    || Double.isNaN(fast[i]) || Double.isNaN(slow[i])) {
                continue;
            }
            if (fast[i - 1] <= slow[i - 1] && fast[i] > slow[i]) {
                expected.add(i + ":BUY");
            } else if (fast[i - 1] >= slow[i - 1] && fast[i] < slow[i]) {
                expected.add(i + ":SELL");
            }
        }

        assertEquals(expected, actual);
        assertFalse(actual.isEmpty(), "先跌后涨行情应至少产生一次金叉信号");
    }

    @Test
    @DisplayName("止损距离与目标盈亏比取自集中配置（不写死）")
    void signalUsesConfiguredRiskParams() {
        BacktestProperties props = weightedProps();
        props.getStrategy().setAtrStopMult(2.0);
        props.getStrategy().setTargetRr(3.0);
        EmaAtrRuleStrategy strategy = new EmaAtrRuleStrategy(props);
        RuleRunner runner = strategy.newRunner();

        List<KlineBar> bars = trendReversal(80);
        EntrySignal first = null;
        for (int i = 0; i < bars.size() && first == null; i++) {
            first = runner.onBar(new ReplayView(bars, i), null);
        }
        assertEquals(3.0, first.targetRr(), 1e-9);
        assertFalse(Double.isNaN(first.stopDistance()));
    }
}
