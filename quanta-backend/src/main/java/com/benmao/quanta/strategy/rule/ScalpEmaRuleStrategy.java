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
 * 「演练模式」高频规则策略（RULE 模式，仅用于回测对照，供 {@code DRILL} 策略引用）：
 * <b>EMA 金叉死叉 + ATR 止损，不做任何趋势过滤</b>。
 *
 * <p>相对 M9 趋势策略，本策略<b>移除 ADX 门控</b>，只要均线交叉即出信号，因而开单频率极高
 * （GOLD M1、90 天实测约 2900+ 笔），用于验证「AI 是否真的会下单」；但高成本档（30 点差）下期望为负，
 * 属正常现象，<b>不得</b>作为正式策略上线，只能经 {@code /api/strategy/{id}/drill-approve} 置 {@code DRILL}。
 *
 * <p>实现约定与 {@link TrendEmaAdxRuleStrategy} 一致：增量有状态、TA-Lib 口径、仅经 {@link ReplayView}
 * 读取"当前及之前"K 线（杜绝未来函数）；参数全部取自 {@link StrategyProperties.Scalp} 集中配置。
 */
@Component
public class ScalpEmaRuleStrategy implements RuleStrategy {

    public static final String KEY = "SCALP_EMA";

    private final StrategyProperties props;

    public ScalpEmaRuleStrategy(StrategyProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "演练-高频 EMA 金叉死叉 + ATR 止损（无趋势过滤）";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        StrategyProperties.Scalp p = props.getScalp();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emaFast", p.getEmaFast());
        m.put("emaSlow", p.getEmaSlow());
        m.put("atrPeriod", p.getAtrPeriod());
        m.put("atrStopMult", p.getAtrStopMult());
        m.put("targetRr", p.getTargetRr());
        return m;
    }

    @Override
    public RuleRunner newRunner() {
        StrategyProperties.Scalp p = props.getScalp();
        return new ScalpRunner(p.getEmaFast(), p.getEmaSlow(), p.getAtrPeriod(),
                p.getAtrStopMult(), p.getTargetRr());
    }

    /** 有状态运行实例（增量 EMA / ATR）。 */
    static final class ScalpRunner implements RuleRunner {

        private final int fastPeriod;
        private final int slowPeriod;
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

        ScalpRunner(int fastPeriod, int slowPeriod, int atrPeriod, double atrStopMult, double targetRr) {
            this.fastPeriod = fastPeriod;
            this.slowPeriod = slowPeriod;
            this.atrPeriod = atrPeriod;
            this.atrStopMult = atrStopMult;
            this.targetRr = targetRr;
        }

        @Override
        public int warmupBars() {
            return Math.max(slowPeriod, atrPeriod);
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
            prevClose = close;

            if (position != null) {
                return null;
            }
            if (view.cursor() < warmupBars()) {
                return null;
            }
            if (Double.isNaN(emaFast) || Double.isNaN(emaSlow) || Double.isNaN(prevFast) || Double.isNaN(prevSlow)
                    || Double.isNaN(atr)) {
                return null;
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
    }
}
