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

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「笨猫黄金」规则策略（RULE 模式）：<b>单仓趋势跟随</b>，改写自 MT5 源码
 * 「谷歌黄金，修改了动态止盈损，动态仓位，.mq5」。
 *
 * <p>进场规则（一次仅一笔，空仓时才可能出信号）：
 * <ul>
 *   <li>价格站上 EMA({@code ema-period}=52) 且 RSI({@code rsi-period}) 高于 {@code rsi-midline} → 做多；</li>
 *   <li>价格跌破 EMA52 且 RSI 低于中线 → 做空；</li>
 *   <li>ADX({@code adx-period}) 须 &gt; {@code adx-threshold}（趋势强度过滤，源码 InpADXThreshold=20）；</li>
 *   <li>仅在 {@code [start-hour, end-hour)} 时段内下单（源码 08:00–20:00 平台时间过滤）。</li>
 * </ul>
 *
 * <p>出场：止损距离 = ATR × {@code atr-stop-mult}(2.0)，止盈 = 止损距离 × {@code target-rr}(2.0)，
 * 等价源码的 SL=2×ATR / TP=4×ATR。
 *
 * <p><b>源码有、本项目未覆盖</b>（{@link EntrySignal} 只表达「固定止损距离 + 目标盈亏比」）：
 * 源码的 1.5×ATR 保本移动止损、按「余额 × 风险%」的动态手数；此外源码里名为 SuperTrend / QQE 的两处
 * 实为注释占位，其真实行为就是「close 对 EMA52」与「RSI 对 50」，此处按其<b>实际行为</b>落地。
 *
 * <p>实现为<b>增量有状态</b>：EMA 用前 N 个 SMA 播种、RSI/ATR/ADX 用 Wilder 平滑（与 M4
 * {@code IndicatorService} 口径一致）；仅经 {@link ReplayView} 读"当前及之前"K 线，杜绝未来函数。
 */
@Component
public class BenmaoGoldRuleStrategy implements RuleStrategy {

    public static final String KEY = "BENMAO_GOLD";

    private final StrategyProperties props;

    public BenmaoGoldRuleStrategy(StrategyProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "笨猫黄金（EMA52 趋势 + ADX 过滤 + ATR 止损）";
    }

    @Override
    public Map<String, Object> indicatorParams() {
        StrategyProperties.BenmaoGold p = props.getBenmaoGold();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emaPeriod", p.getEmaPeriod());
        m.put("rsiPeriod", p.getRsiPeriod());
        m.put("rsiMidline", p.getRsiMidline());
        m.put("adxPeriod", p.getAdxPeriod());
        m.put("adxThreshold", p.getAdxThreshold());
        m.put("atrPeriod", p.getAtrPeriod());
        m.put("atrStopMult", p.getAtrStopMult());
        m.put("targetRr", p.getTargetRr());
        m.put("useTimeFilter", p.isUseTimeFilter());
        m.put("startHour", p.getStartHour());
        m.put("endHour", p.getEndHour());
        m.put("timeFilterZone", p.getTimeFilterZone());
        return m;
    }

    @Override
    public RuleRunner newRunner() {
        StrategyProperties.BenmaoGold p = props.getBenmaoGold();
        return new BenmaoGoldRunner(p.getEmaPeriod(), p.getRsiPeriod(), p.getRsiMidline(),
                p.getAdxPeriod(), p.getAdxThreshold(), p.getAtrPeriod(), p.getAtrStopMult(),
                p.getTargetRr(), p.isUseTimeFilter(), p.getStartHour(), p.getEndHour(),
                ZoneId.of(p.getTimeFilterZone()));
    }

    /** 有状态运行实例（增量 EMA / RSI / ADX / ATR + 时段过滤）。 */
    static final class BenmaoGoldRunner implements RuleRunner {

        private final int emaPeriod;
        private final int rsiPeriod;
        private final double rsiMidline;
        private final int adxPeriod;
        private final double adxThreshold;
        private final int atrPeriod;
        private final double atrStopMult;
        private final double targetRr;
        private final boolean useTimeFilter;
        private final int startHour;
        private final int endHour;
        private final ZoneId zone;

        // EMA 状态
        private double emaSum;
        private int emaCount;
        private double ema = Double.NaN;

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

        // ADX 状态（Wilder 平滑）
        private double prevHigh = Double.NaN;
        private double prevLow = Double.NaN;
        private double sTr;
        private double sPdm;
        private double sNdm;
        private double adxSeedSum;
        private int adxSeedCount;
        private double adx = Double.NaN;

        private double prevClose = Double.NaN;

        BenmaoGoldRunner(int emaPeriod, int rsiPeriod, double rsiMidline, int adxPeriod, double adxThreshold,
                         int atrPeriod, double atrStopMult, double targetRr, boolean useTimeFilter,
                         int startHour, int endHour, ZoneId zone) {
            this.emaPeriod = emaPeriod;
            this.rsiPeriod = rsiPeriod;
            this.rsiMidline = rsiMidline;
            this.adxPeriod = adxPeriod;
            this.adxThreshold = adxThreshold;
            this.atrPeriod = atrPeriod;
            this.atrStopMult = atrStopMult;
            this.targetRr = targetRr;
            this.useTimeFilter = useTimeFilter;
            this.startHour = startHour;
            this.endHour = endHour;
            this.zone = zone;
        }

        @Override
        public int warmupBars() {
            return Math.max(Math.max(emaPeriod, 2 * adxPeriod), Math.max(atrPeriod, rsiPeriod + 1));
        }

        @Override
        public EntrySignal onBar(ReplayView view, OpenPosition position) {
            KlineBar bar = view.current();
            double close = bar.close();

            rsi = nextRsi(rsi, close);
            atr = nextAtr(atr, bar);
            ema = nextEma(ema, close);
            adx = nextAdx(adx, bar, view.cursor());

            prevClose = close;
            prevHigh = bar.high();
            prevLow = bar.low();

            // 已有持仓 → 不动作（一次仅一笔），但仍推进指标状态
            if (position != null) {
                return null;
            }
            if (view.cursor() < warmupBars()) {
                return null;
            }
            if (Double.isNaN(ema) || Double.isNaN(rsi) || Double.isNaN(atr) || Double.isNaN(adx)) {
                return null;
            }
            if (useTimeFilter) {
                int hour = bar.instant().atZone(zone).getHour();
                if (hour < startHour || hour >= endHour) {
                    return null; // 时段外不交易（源码 08:00–20:00 平台时间过滤）
                }
            }
            if (adx <= adxThreshold) {
                return null; // 趋势强度不足 → 过滤
            }

            if (close > ema && rsi > rsiMidline) {
                return new EntrySignal(RiskSide.BUY, atr * atrStopMult, targetRr);
            }
            if (close < ema && rsi < rsiMidline) {
                return new EntrySignal(RiskSide.SELL, atr * atrStopMult, targetRr);
            }
            return null;
        }

        /** EMA：前 period 个的 SMA 播种，之后按 k=2/(period+1) 递推（TA-Lib 约定）。 */
        private double nextEma(double current, double value) {
            if (emaCount < emaPeriod) {
                emaSum += value;
                emaCount++;
                if (emaCount == emaPeriod) {
                    return emaSum / emaPeriod;
                }
                return Double.NaN;
            }
            double k = 2.0 / (emaPeriod + 1.0);
            return value * k + current * (1 - k);
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
