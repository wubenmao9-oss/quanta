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
 * 「演练模式」高频薅点规则策略（RULE 模式，仅用于回测对照，供 {@code DRILL} 策略引用）：
 * <b>EMA 判方向 + 固定美元距离止损，无任何趋势过滤，空仓即出信号</b>。
 *
 * <p>与 {@link ScalpEmaRuleStrategy} 的区别：止损<b>不是</b> ATR 动态值，而是固定的
 * {@code slDistanceUsd}（GOLD 上 2.5 USD = 250 points），止盈 = 止损 × targetRr（3.0 USD）；
 * 方向判定同样以快/慢 EMA 为准，且<b>没有金叉死叉的「只在新交叉时开」限制</b> ——
 * 只要未持仓，每根 K 线都给出「顺当前排列方向」的开仓意图，从而把开仓频率拉到最大
 * （一次仅一笔），用于验证「AI 是否真的会高频下单」。
 *
 * <p>定位：<b>不追求正期望</b>（高成本档下必为负），<b>不得</b>作为正式策略上线，
 * 只能经 {@code /api/strategy/{id}/drill-approve} 置 {@code DRILL}。
 *
 * <p>实现约定与其它规则策略一致：增量有状态、TA-Lib 口径、仅经 {@link ReplayView}
 * 读取"当前及之前"K 线（杜绝未来函数）；参数全部取自 {@link StrategyProperties.Tiny} 集中配置。
 */
@Component
public class TinyScalpRuleStrategy implements RuleStrategy {

    public static final String KEY = "TINY_SCALP";

    private final StrategyProperties props;

    public TinyScalpRuleStrategy(StrategyProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "演练-高频薅点（EMA 判方向 + 固定 USD 止损）";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        StrategyProperties.Tiny p = props.getTiny();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emaFast", p.getEmaFast());
        m.put("emaSlow", p.getEmaSlow());
        m.put("slDistanceUsd", p.getSlDistanceUsd());
        m.put("targetRr", p.getTargetRr());
        return m;
    }

    @Override
    public RuleRunner newRunner() {
        StrategyProperties.Tiny p = props.getTiny();
        return new TinyRunner(p.getEmaFast(), p.getEmaSlow(), p.getSlDistanceUsd(), p.getTargetRr());
    }

    /** 有状态运行实例（增量 EMA；固定止损距离）。 */
    static final class TinyRunner implements RuleRunner {

        private final int fastPeriod;
        private final int slowPeriod;
        private final double slDistanceUsd;
        private final double targetRr;

        // EMA 状态
        private double fastSum;
        private int fastCount;
        private double emaFast = Double.NaN;
        private double slowSum;
        private int slowCount;
        private double emaSlow = Double.NaN;

        // 最近一根收盘价（快慢线重合时用于判定方向）
        private double prevClose = Double.NaN;

        TinyRunner(int fastPeriod, int slowPeriod, double slDistanceUsd, double targetRr) {
            this.fastPeriod = fastPeriod;
            this.slowPeriod = slowPeriod;
            this.slDistanceUsd = slDistanceUsd;
            this.targetRr = targetRr;
        }

        @Override
        public int warmupBars() {
            return slowPeriod;
        }

        @Override
        public EntrySignal onBar(ReplayView view, OpenPosition position) {
            KlineBar bar = view.current();
            double close = bar.close();

            emaFast = nextEma(emaFast, close, fastPeriod, true);
            emaSlow = nextEma(emaSlow, close, slowPeriod, false);
            double prev = prevClose;
            prevClose = close;

            // 已有持仓 → 不动作（一次仅一笔），但仍推进指标状态
            if (position != null) {
                return null;
            }
            if (view.cursor() < warmupBars()) {
                return null;
            }
            if (Double.isNaN(emaFast) || Double.isNaN(emaSlow)) {
                return null;
            }

            RiskSide side;
            if (emaFast > emaSlow) {
                side = RiskSide.BUY;
            } else if (emaFast < emaSlow) {
                side = RiskSide.SELL;
            } else {
                // 快慢线重合/方向不明：与最近一根 K 线涨跌方向一致（无前一根则默认做多）
                side = (Double.isNaN(prev) || close >= prev) ? RiskSide.BUY : RiskSide.SELL;
            }
            return new EntrySignal(side, slDistanceUsd, targetRr);
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
    }
}
