package com.benmao.quanta.backtest.rule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.backtest.BacktestProperties;
import com.benmao.quanta.backtest.EntrySignal;
import com.benmao.quanta.backtest.OpenPosition;
import com.benmao.quanta.backtest.ReplayView;
import com.benmao.quanta.backtest.RuleRunner;
import com.benmao.quanta.backtest.RuleStrategy;
import com.benmao.quanta.risk.RiskSide;
import org.springframework.stereotype.Component;

/**
 * 内置示例规则策略（RULE 模式）：<b>EMA 金叉死叉 + ATR 止损</b>。
 *
 * <p>规则：
 * <ul>
 *   <li>快线 EMA 上穿慢线（金叉）→ 做多；下穿（死叉）→ 做空；</li>
 *   <li>止损距离 = ATR × {@code atr-stop-mult}；止盈 = 止损距离 × {@code target-rr}；</li>
 *   <li>同一时刻最多一笔持仓（引擎保证），持仓期间不新开。</li>
 * </ul>
 *
 * <p>实现为<b>增量有状态</b>：EMA 沿用 TA-Lib 约定（前 N 个 SMA 播种，之后 k=2/(N+1) 递推），
 * ATR 用 Wilder 平滑，与 {@code IndicatorService} 口径一致；每次回测 {@link #newRunner()} 独立实例，
 * 只经 {@link ReplayView} 读取"当前及之前"的 K 线，杜绝未来函数。
 *
 * <p>该策略同时作为 M9 真策略（Prompt → 规则）的落地模板。
 */
@Component
public class EmaAtrRuleStrategy implements RuleStrategy {

    public static final String KEY = "EMA_ATR";

    private final BacktestProperties props;

    public EmaAtrRuleStrategy(BacktestProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "EMA 金叉死叉 + ATR 止损";
    }

    @Override
    public RuleRunner newRunner() {
        BacktestProperties.Strategy p = props.getStrategy();
        return new EmaAtrRunner(p.getEmaFast(), p.getEmaSlow(), p.getAtrPeriod(),
                p.getAtrStopMult(), p.getTargetRr());
    }

    /** 有状态运行实例。 */
    static final class EmaAtrRunner implements RuleRunner {

        private final int fastPeriod;
        private final int slowPeriod;
        private final int atrPeriod;
        private final double atrStopMult;
        private final double targetRr;

        // EMA 运行状态
        private double fastSum;
        private double slowSum;
        private int fastCount;
        private int slowCount;
        private double emaFast = Double.NaN;
        private double emaSlow = Double.NaN;
        private double prevFast = Double.NaN;
        private double prevSlow = Double.NaN;

        // ATR 运行状态（Wilder 平滑）
        private double trSum;
        private int trCount;
        private double atr = Double.NaN;
        private double prevClose = Double.NaN;

        EmaAtrRunner(int fastPeriod, int slowPeriod, int atrPeriod, double atrStopMult, double targetRr) {
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

            // 先保存上一根的 EMA（用于交叉判定），再更新到当前根
            prevFast = emaFast;
            prevSlow = emaSlow;
            emaFast = nextEma(emaFast, close, fastPeriod, true);
            emaSlow = nextEma(emaSlow, close, slowPeriod, false);
            atr = nextAtr(atr, bar);
            prevClose = close;

            if (position != null) {
                return null; // 持仓中不新开
            }
            if (view.cursor() < warmupBars()) {
                return null;
            }
            if (Double.isNaN(emaFast) || Double.isNaN(emaSlow)
                    || Double.isNaN(prevFast) || Double.isNaN(prevSlow) || Double.isNaN(atr)) {
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
