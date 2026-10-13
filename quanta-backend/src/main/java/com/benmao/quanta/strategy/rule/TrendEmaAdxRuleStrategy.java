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
 * M9 首批<b>趋势策略</b>（RULE 模式）：<b>EMA 金叉死叉 + ADX 趋势强度过滤 + ATR 止损</b>。
 *
 * <p>相对 M8 内置示例的改进：增加 <b>ADX 趋势强度门控</b>——仅在 ADX ≥ 阈值（确有趋势）时才接受均线交叉信号，
 * 抑制震荡期的频繁无效交易，正对 M8 实测暴露的「过度交易 → 利润被点差吃掉」痛点。
 *
 * <p>规则：
 * <ul>
 *   <li>快线 EMA 上穿慢线（金叉）→ 做多；下穿（死叉）→ 做空；</li>
 *   <li>但仅当 ADX ≥ {@code adx-threshold} 时才发出信号（趋势过滤）；</li>
 *   <li>止损距离 = ATR × {@code atr-stop-mult}；止盈 = 止损距离 × {@code target-rr}；</li>
 *   <li>同一时刻最多一笔持仓（引擎保证），持仓期间不新开。</li>
 * </ul>
 *
 * <p>实现为<b>增量有状态</b>：EMA/ATR 沿用 TA-Lib 约定，ADX 用 Wilder 平滑（+DI/−DI 自 period 起有效、
 * ADX 自 2·period−1 起有效），与 M4 {@code IndicatorService} 口径一致；仅经 {@link ReplayView} 读"当前及之前"K 线。
 */
@Component
public class TrendEmaAdxRuleStrategy implements RuleStrategy {

    public static final String KEY = "TREND_EMA_ADX";

    private final StrategyProperties props;

    public TrendEmaAdxRuleStrategy(StrategyProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "趋势-EMA 金叉死叉 + ADX 过滤 + ATR 止损";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        StrategyProperties.Trend p = props.getTrend();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emaFast", p.getEmaFast());
        m.put("emaSlow", p.getEmaSlow());
        m.put("adxPeriod", p.getAdxPeriod());
        m.put("adxThreshold", p.getAdxThreshold());
        m.put("atrPeriod", p.getAtrPeriod());
        m.put("atrStopMult", p.getAtrStopMult());
        m.put("targetRr", p.getTargetRr());
        return m;
    }

    @Override
    public RuleRunner newRunner() {
        StrategyProperties.Trend p = props.getTrend();
        return new TrendRunner(p.getEmaFast(), p.getEmaSlow(), p.getAdxPeriod(), p.getAdxThreshold(),
                p.getAtrPeriod(), p.getAtrStopMult(), p.getTargetRr());
    }

    /** 有状态运行实例（增量 EMA / ADX / ATR）。 */
    static final class TrendRunner implements RuleRunner {

        private final int fastPeriod;
        private final int slowPeriod;
        private final int adxPeriod;
        private final double adxThreshold;
        private final int atrPeriod;
        private final double atrStopMult;
        private final double targetRr;

        // EMA 状态
        private double fastSum;
        private int fastCount;
        private double emaFast = Double.NaN;
        private double prevFast = Double.NaN;
        private double slowSum;
        private int slowCount;
        private double emaSlow = Double.NaN;
        private double prevSlow = Double.NaN;

        // ATR 状态（Wilder 平滑）
        private double trSum;
        private int trCount;
        private double atr = Double.NaN;
        private double prevClose = Double.NaN;

        // ADX 状态（Wilder 平滑）
        private double prevHigh = Double.NaN;
        private double prevLow = Double.NaN;
        private double sTr;
        private double sPdm;
        private double sNdm;
        private double adxSeedSum;
        private int adxSeedCount;
        private double adx = Double.NaN;

        TrendRunner(int fastPeriod, int slowPeriod, int adxPeriod, double adxThreshold,
                    int atrPeriod, double atrStopMult, double targetRr) {
            this.fastPeriod = fastPeriod;
            this.slowPeriod = slowPeriod;
            this.adxPeriod = adxPeriod;
            this.adxThreshold = adxThreshold;
            this.atrPeriod = atrPeriod;
            this.atrStopMult = atrStopMult;
            this.targetRr = targetRr;
        }

        @Override
        public int warmupBars() {
            return Math.max(Math.max(slowPeriod, 2 * adxPeriod), atrPeriod);
        }

        @Override
        public EntrySignal onBar(ReplayView view, OpenPosition position) {
            KlineBar bar = view.current();
            double close = bar.close();

            prevFast = emaFast;
            prevSlow = emaSlow;
            emaFast = nextEma(emaFast, close, fastPeriod, true);
            emaSlow = nextEma(emaSlow, close, slowPeriod, false);
            atr = nextAtr(atr, bar);
            adx = nextAdx(adx, bar, view.cursor());
            prevClose = close;
            prevHigh = bar.high();
            prevLow = bar.low();

            if (position != null) {
                return null;
            }
            if (view.cursor() < warmupBars()) {
                return null;
            }
            if (Double.isNaN(emaFast) || Double.isNaN(emaSlow) || Double.isNaN(prevFast) || Double.isNaN(prevSlow)
                    || Double.isNaN(atr) || Double.isNaN(adx)) {
                return null;
            }
            if (adx < adxThreshold) {
                return null; // 趋势强度不足（震荡期）→ 过滤，不出信号
            }

            boolean goldenCross = prevFast <= prevSlow && emaFast > emaSlow;
            boolean deathCross = prevFast >= prevSlow && emaFast < emaSlow;
            if (goldenCross) {
                return new EntrySignal(RiskSide.BUY, atr * atrStopMult, targetRr);
            }
            if (deathCross) {
                return new EntrySignal(RiskSide.SELL, atr * atrStopMult, targetRr);
            }
            return null;
        }

        /** EMA：前 period 个的 SMA 播种，之后按 k=2/(period+1) 递推（TA-Lib 约定）。 */
        private double nextEma(double current, double value, int period, boolean fast) {
            if (fast) {
                if (fastCount < period) {
                    fastSum += value;
                    fastCount++;
                    if (fastCount == period) {
                        return fastSum / period;
                    }
                    return Double.NaN;
                }
                double k = 2.0 / (period + 1.0);
                return value * k + current * (1 - k);
            }
            if (slowCount < period) {
                slowSum += value;
                slowCount++;
                if (slowCount == period) {
                    return slowSum / period;
                }
                return Double.NaN;
            }
            double k = 2.0 / (period + 1.0);
            return value * k + current * (1 - k);
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

        /**
         * ADX（Wilder 平滑）：与 {@code IndicatorService.adx} 口径一致——+DI/−DI 自索引 period 起有效，
         * ADX 自索引 2·period−1 起有效（先对 period 个 DX 取均值播种，之后 Wilder 递推）。
         */
        private double nextAdx(double current, KlineBar bar, int index) {
            if (index == 0 || Double.isNaN(prevHigh)) {
                return current; // 首根无前值，尚未起算
            }
            double up = bar.high() - prevHigh;
            double dn = prevLow - bar.low();
            double pdm = (up > dn && up > 0) ? up : 0;
            double ndm = (dn > up && dn > 0) ? dn : 0;
            double tr = Math.max(bar.high() - bar.low(),
                    Math.max(Math.abs(bar.high() - prevClose), Math.abs(bar.low() - prevClose)));

            if (index <= adxPeriod) {
                sTr += tr;
                sPdm += pdm;
                sNdm += ndm;
            } else {
                sTr = sTr - sTr / adxPeriod + tr;
                sPdm = sPdm - sPdm / adxPeriod + pdm;
                sNdm = sNdm - sNdm / adxPeriod + ndm;
            }
            if (index < adxPeriod) {
                return current; // DX 尚未起算
            }
            double pdi = sTr == 0 ? 0 : 100.0 * sPdm / sTr;
            double ndi = sTr == 0 ? 0 : 100.0 * sNdm / sTr;
            double sum = pdi + ndi;
            double dx = sum == 0 ? 0 : 100.0 * Math.abs(pdi - ndi) / sum;

            int first = 2 * adxPeriod - 1;
            if (index <= first) {
                adxSeedSum += dx;
                adxSeedCount++;
                if (index == first && adxSeedCount == adxPeriod) {
                    return adxSeedSum / adxPeriod;
                }
                return current;
            }
            if (Double.isNaN(current)) {
                return Double.NaN; // 播种未完成（数据不足以覆盖首段）
            }
            return (current * (adxPeriod - 1) + dx) / adxPeriod;
        }
    }
}
