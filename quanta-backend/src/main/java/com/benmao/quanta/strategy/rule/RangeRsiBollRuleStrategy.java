package com.benmao.quanta.strategy.rule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.backtest.EntrySignal;
import com.benmao.quanta.backtest.OpenPosition;
import com.benmao.quanta.backtest.ReplayView;
import com.benmao.quanta.backtest.RuleRunner;
import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.strategy.StrategyProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M9 首批<b>震荡策略</b>（RULE 模式）：<b>RSI 超买超卖 + 布林带轨道回归 + ATR 止损</b>。
 *
 * <p>规则（区间回归，与趋势策略互补，构成"高胜率低盈亏比"结构）：
 * <ul>
 *   <li>价格触及<b>下轨</b>且 RSI 超卖 → 做多（预期回归中轨）；</li>
 *   <li>价格触及<b>上轨</b>且 RSI 超买 → 做空；</li>
 *   <li>止损距离 = ATR × {@code atr-stop-mult}；止盈 = 止损距离 × {@code target-rr}；</li>
 *   <li>同一时刻最多一笔持仓（引擎保证），持仓期间不新开。</li>
 * </ul>
 *
 * <p>实现为<b>增量有状态</b>：RSI 用 Wilder 平滑（自索引 period 起有效）、ATR 用 Wilder 平滑，
 * 布林带用"最近 period 根收盘"的总体标准差（与 M4 {@code IndicatorService} 口径一致）；
 * 仅经 {@link ReplayView} 读"当前及之前"K 线，杜绝未来函数。
 */
@Component
public class RangeRsiBollRuleStrategy implements RuleStrategy {

    public static final String KEY = "RANGE_RSI_BOLL";

    private final StrategyProperties props;

    public RangeRsiBollRuleStrategy(StrategyProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "震荡-RSI 超买超卖 + 布林带回归 + ATR 止损";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        StrategyProperties.Range p = props.getRange();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rsiPeriod", p.getRsiPeriod());
        m.put("rsiOversold", p.getRsiOversold());
        m.put("rsiOverbought", p.getRsiOverbought());
        m.put("bollPeriod", p.getBollPeriod());
        m.put("bollK", p.getBollK());
        m.put("atrPeriod", p.getAtrPeriod());
        m.put("atrStopMult", p.getAtrStopMult());
        m.put("targetRr", p.getTargetRr());
        return m;
    }

    @Override
    public RuleRunner newRunner() {
        StrategyProperties.Range p = props.getRange();
        return new RangeRunner(p.getRsiPeriod(), p.getRsiOversold(), p.getRsiOverbought(),
                p.getBollPeriod(), p.getBollK(), p.getAtrPeriod(), p.getAtrStopMult(), p.getTargetRr());
    }

    /** 有状态运行实例（增量 RSI / ATR + 布林带滚动窗口）。 */
    static final class RangeRunner implements RuleRunner {

        private final int rsiPeriod;
        private final double rsiOversold;
        private final double rsiOverbought;
        private final int bollPeriod;
        private final double bollK;
        private final int atrPeriod;
        private final double atrStopMult;
        private final double targetRr;

        // RSI 状态（Wilder 平滑）
        private double gainSum;
        private double lossSum;
        private int rsiCount;
        private double avgGain;
        private double avgLoss;
        private double rsi = Double.NaN;

        // ATR 状态（Wilder 平滑）
        private double trSum;
        private int trCount;
        private double atr = Double.NaN;
        private double prevClose = Double.NaN;

        RangeRunner(int rsiPeriod, double rsiOversold, double rsiOverbought,
                    int bollPeriod, double bollK, int atrPeriod, double atrStopMult, double targetRr) {
            this.rsiPeriod = rsiPeriod;
            this.rsiOversold = rsiOversold;
            this.rsiOverbought = rsiOverbought;
            this.bollPeriod = bollPeriod;
            this.bollK = bollK;
            this.atrPeriod = atrPeriod;
            this.atrStopMult = atrStopMult;
            this.targetRr = targetRr;
        }

        @Override
        public int warmupBars() {
            return Math.max(Math.max(bollPeriod, rsiPeriod + 1), atrPeriod);
        }

        @Override
        public EntrySignal onBar(ReplayView view, OpenPosition position) {
            KlineBar bar = view.current();
            double close = bar.close();
            int index = view.cursor();

            rsi = nextRsi(rsi, close);
            atr = nextAtr(atr, bar);

            if (position != null) {
                prevClose = close;
                return null;
            }
            if (index < warmupBars()) {
                prevClose = close;
                return null;
            }
            if (Double.isNaN(rsi) || Double.isNaN(atr)) {
                prevClose = close;
                return null;
            }

            double mean = 0;
            for (int j = 0; j < bollPeriod; j++) {
                mean += view.bar(index - j).close();
            }
            mean /= bollPeriod;
            double sumSq = 0;
            for (int j = 0; j < bollPeriod; j++) {
                double d = view.bar(index - j).close() - mean;
                sumSq += d * d;
            }
            double sd = Math.sqrt(sumSq / bollPeriod);
            double upper = mean + bollK * sd;
            double lower = mean - bollK * sd;

            prevClose = close;

            if (close <= lower && rsi < rsiOversold) {
                return new EntrySignal(RiskSide.BUY, atr * atrStopMult, targetRr);
            }
            if (close >= upper && rsi > rsiOverbought) {
                return new EntrySignal(RiskSide.SELL, atr * atrStopMult, targetRr);
            }
            return null;
        }

        /** RSI：Wilder 平滑，前 period 个涨跌幅均值播种（自索引 period 起有效）。 */
        private double nextRsi(double current, double close) {
            if (Double.isNaN(prevClose)) {
                return current; // 首根无前收，尚未起算
            }
            double d = close - prevClose;
            double gain = d > 0 ? d : 0;
            double loss = d < 0 ? -d : 0;
            if (rsiCount < rsiPeriod) {
                gainSum += gain;
                lossSum += loss;
                rsiCount++;
                if (rsiCount == rsiPeriod) {
                    avgGain = gainSum / rsiPeriod;
                    avgLoss = lossSum / rsiPeriod;
                    return rsiValue(avgGain, avgLoss);
                }
                return current;
            }
            avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod;
            avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod;
            return rsiValue(avgGain, avgLoss);
        }

        private static double rsiValue(double avgGain, double avgLoss) {
            if (avgLoss == 0) {
                return avgGain == 0 ? 50.0 : 100.0;
            }
            double rs = avgGain / avgLoss;
            return 100.0 - 100.0 / (1.0 + rs);
        }

        /** ATR：TR 首根用 high−low；前 period 个的均值播种，之后 Wilder 平滑。 */
        private double nextAtr(double current, KlineBar bar) {
            double tr = Double.isNaN(prevClose)
                    ? bar.high() - bar.low()
                    : Math.max(bar.high() - bar.low(),
                            Math.max(Math.abs(bar.high() - prevClose), Math.abs(bar.low() - prevClose)));
            if (trCount < atrPeriod) {
                trSum += tr;
                trCount++;
                if (trCount == atrPeriod) {
                    return trSum / atrPeriod;
                }
                return Double.NaN;
            }
            return (current * (atrPeriod - 1) + tr) / atrPeriod;
        }
    }
}
