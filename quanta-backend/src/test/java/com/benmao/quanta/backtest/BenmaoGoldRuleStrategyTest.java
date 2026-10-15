package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.strategy.StrategyProperties;
import com.benmao.quanta.strategy.rule.BenmaoGoldRuleStrategy;
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
 * 「笨猫黄金」（BENMAO_GOLD）单测：EMA52 定方向 + RSI 相对 50 的动量 + ADX 过滤 + 时段过滤，
 * 止损 = ATR×倍数、盈亏比取自集中配置；一次仅一笔。
 *
 * <p>与 {@link TrendEmaAdxRuleStrategyTest} 同处 {@code com.benmao.quanta.backtest}，
 * 以便直接构造包内可见的 {@link ReplayView}（游标受限视图由回测引擎层专有）。
 * 指标口径一律与 M4 {@link IndicatorService} 逐点比对。
 */
class BenmaoGoldRuleStrategyTest {

    private static final long T0 = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond();

    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(new IndicatorProperties()));

    /** 测试用集中配置：缩短周期以减小数据量，关闭时段过滤（时段过滤单独用例覆盖）。 */
    private static StrategyProperties props() {
        StrategyProperties p = new StrategyProperties();
        StrategyProperties.BenmaoGold g = p.getBenmaoGold();
        g.setEmaPeriod(10);
        g.setRsiPeriod(5);
        g.setAdxPeriod(5);
        g.setAtrPeriod(5);
        g.setAdxThreshold(20.0);
        g.setAtrStopMult(2.0);
        g.setTargetRr(2.0);
        g.setUseTimeFilter(false);
        return p;
    }

    /** 前段上行、后段下行的确定性行情（制造多头/空头两段，保证 ADX 抬升到阈值之上）。 */
    private static List<KlineBar> series(int n) {
        List<KlineBar> bars = new ArrayList<>();
        double prev = 2000;
        for (int i = 0; i < n; i++) {
            double c = (i < n / 2) ? prev + 0.8 : prev - 0.8;
            double open = prev;
            double high = Math.max(open, c) + 0.2;
            double low = Math.min(open, c) - 0.2;
            bars.add(new KlineBar(T0 + i * 60L, open, high, low, c, 100));
            prev = c;
        }
        return bars;
    }

    /** 每根相隔 1 小时的行情，小时序列为 {@code startHour..startHour+n-1}（用于时段过滤用例）。 */
    private static List<KlineBar> hourlySeries(int n, int startHourUtc) {
        long base = Instant.parse("2026-09-12T00:00:00Z").getEpochSecond() + startHourUtc * 3600L;
        List<KlineBar> bars = new ArrayList<>();
        double prev = 2000;
        for (int i = 0; i < n; i++) {
            double c = (i < n / 2) ? prev + 0.8 : prev - 0.8;
            double open = prev;
            double high = Math.max(open, c) + 0.2;
            double low = Math.min(open, c) - 0.2;
            bars.add(new KlineBar(base + i * 3600L, open, high, low, c, 100));
            prev = c;
        }
        return bars;
    }

    private static double[] closes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            out[i] = bars.get(i).close();
        }
        return out;
    }

    @Test
    @DisplayName("逐点与 M4 指标层一致：EMA/RSI/ADX 只经 ReplayView 读当前及之前，方向与动量同向才开仓")
    void matchesIndicatorLayer() {
        StrategyProperties p = props();
        RuleRunner runner = new BenmaoGoldRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(120);
        double[] closes = closes(bars);
        double[] ema = indicator.ema(closes, 10);
        double[] rsi = indicator.rsi(closes, 5);
        double[] adx = indicator.adx(bars, 5).adx();

        int signals = 0;
        boolean sawBuy = false;
        boolean sawSell = false;
        for (int i = 0; i < bars.size(); i++) {
            EntrySignal s = runner.onBar(new ReplayView(bars, i), null);
            if (i < runner.warmupBars() || Double.isNaN(ema[i]) || Double.isNaN(rsi[i]) || Double.isNaN(adx[i])) {
                assertNull(s, "预热期/指标未就绪不得出信号：i=" + i);
                continue;
            }
            boolean buy = closes[i] > ema[i] && rsi[i] > 50.0;
            boolean sell = closes[i] < ema[i] && rsi[i] < 50.0;
            if (adx[i] <= 20.0 || !(buy || sell)) {
                assertNull(s, "ADX 不足或方向/动量不同向时应观望：i=" + i);
                continue;
            }
            assertNotNull(s, "满足条件时应出信号：i=" + i);
            assertEquals(buy ? RiskSide.BUY : RiskSide.SELL, s.side(), "方向应与 EMA 基准一致：i=" + i);
            assertEquals(2.0, s.targetRr(), 1e-9, "盈亏比应为配置值 2.0");
            sawBuy |= s.side() == RiskSide.BUY;
            sawSell |= s.side() == RiskSide.SELL;
            signals++;
        }
        assertTrue(signals > 0, "该行情应产出信号");
        assertTrue(sawBuy && sawSell, "前段上行应出 BUY、后段下行应出 SELL");
    }

    @Test
    @DisplayName("ADX 过滤：阈值抬高到不可达时一律观望")
    void adxFilterSuppressesSignals() {
        StrategyProperties p = props();
        p.getBenmaoGold().setAdxThreshold(100.0);
        RuleRunner runner = new BenmaoGoldRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(120);

        for (int i = 0; i < bars.size(); i++) {
            assertNull(runner.onBar(new ReplayView(bars, i), null), "ADX 阈值不可达时应一律观望：i=" + i);
        }
    }

    @Test
    @DisplayName("时段过滤：窗口外不出信号，覆盖全时段时与关闭过滤结果一致")
    void timeFilterSuppressesOutsideWindow() {
        // 数据小时序列为 1..20（UTC），故 [0,1) 窗口必然全部落在窗口外
        List<KlineBar> bars = hourlySeries(20, 1);

        StrategyProperties outside = props();
        StrategyProperties.BenmaoGold go = outside.getBenmaoGold();
        go.setUseTimeFilter(true);
        go.setStartHour(0);
        go.setEndHour(1);
        RuleRunner runnerOutside = new BenmaoGoldRuleStrategy(outside).newRunner();
        for (int i = 0; i < bars.size(); i++) {
            assertNull(runnerOutside.onBar(new ReplayView(bars, i), null), "窗口外不得出信号：i=" + i);
        }

        StrategyProperties all = props();
        StrategyProperties.BenmaoGold ga = all.getBenmaoGold();
        ga.setUseTimeFilter(true);
        ga.setStartHour(0);
        ga.setEndHour(24);
        RuleRunner runnerAll = new BenmaoGoldRuleStrategy(all).newRunner();
        int signals = 0;
        for (int i = 0; i < bars.size(); i++) {
            if (runnerAll.onBar(new ReplayView(bars, i), null) != null) {
                signals++;
            }
        }
        assertTrue(signals > 0, "覆盖全时段时应与关闭过滤等价并产出信号");
    }

    @Test
    @DisplayName("止损距离 = ATR × 倍数、盈亏比取自集中配置（不写死）")
    void usesConfiguredRiskParams() {
        StrategyProperties p = props();
        StrategyProperties.BenmaoGold g = p.getBenmaoGold();
        g.setAtrStopMult(3.0);
        g.setTargetRr(1.5);
        RuleRunner runner = new BenmaoGoldRuleStrategy(p).newRunner();
        List<KlineBar> bars = series(120);
        double[] atr = indicator.atr(bars, 5);

        EntrySignal first = null;
        int at = -1;
        for (int i = 0; i < bars.size() && first == null; i++) {
            first = runner.onBar(new ReplayView(bars, i), null);
            at = i;
        }
        assertNotNull(first, "该行情应产出信号");
        assertEquals(1.5, first.targetRr(), 1e-9, "盈亏比应为配置值 1.5");
        assertEquals(atr[at] * 3.0, first.stopDistance(), 1e-9, "止损距离应为 ATR×3.0：i=" + at);
    }

    @Test
    @DisplayName("已有持仓 → 返回 null（一次仅一笔）")
    void noSignalWhileHoldingPosition() {
        RuleRunner runner = new BenmaoGoldRuleStrategy(props()).newRunner();
        List<KlineBar> bars = series(60);
        OpenPosition held = new OpenPosition(RiskSide.BUY, 5, 2010.0, 2007.5, 2013.0);

        for (int i = 0; i < bars.size(); i++) {
            assertNull(runner.onBar(new ReplayView(bars, i), held), "持仓中不得再出信号：i=" + i);
        }
    }
}
