package com.benmao.quanta.strategy.rule;

import com.benmao.mt5.model.KlineBar;

/**
 * 盘面形态判定器（增量有状态，TA-Lib 口径 EMA/ADX）：把 EMA(快/慢) 相对差与 ADX 组合成三类形态
 * {@code MARTIN}（趋势，用马丁）/ {@code GRID}（震荡，用双边网格）/ {@code WAIT}（不动作）。
 *
 * <p>口径（与 {@code IndicatorService} 一致）：EMA 用前 N 个 SMA 播种、之后 {@code k=2/(N+1)} 递推；
 * ADX 的 +DI/−DI 自索引 {@code period} 起有效，ADX 自索引 {@code 2·period−1} 起有效（Wilder 平滑）。
 *
 * <p>本类为回测（P1）与实盘 cycle 执行器（P2）<b>共用</b>，只经调用方传入的「已到游标」K 线更新，杜绝未来函数。
 */
public final class MarketRegimeClassifier {

    /** 盘面形态。 */
    public enum Regime {
        MARTIN, GRID, WAIT
    }

    private final int emaFastPeriod;
    private final int emaSlowPeriod;
    private final int adxPeriod;
    private final double trendEpsilonPct;
    private final double adxGridMax;
    private final double adxTrendMin;

    private final EmaState fast = new EmaState();
    private final EmaState slow = new EmaState();

    private double prevHigh = Double.NaN;
    private double prevLow = Double.NaN;
    private double prevClose = Double.NaN;
    private double sTr;
    private double sPdm;
    private double sNdm;
    private double adxSeedSum;
    private int adxSeedCount;
    private double adx = Double.NaN;

    public MarketRegimeClassifier(int emaFastPeriod, int emaSlowPeriod, int adxPeriod,
                                 double trendEpsilonPct, double adxGridMax, double adxTrendMin) {
        this.emaFastPeriod = Math.max(2, emaFastPeriod);
        this.emaSlowPeriod = Math.max(2, emaSlowPeriod);
        this.adxPeriod = Math.max(2, adxPeriod);
        this.trendEpsilonPct = trendEpsilonPct;
        this.adxGridMax = adxGridMax;
        this.adxTrendMin = adxTrendMin;
    }

    /** 需要的最少 K 线根数。 */
    public int warmupBars() {
        return Math.max(emaSlowPeriod, 2 * adxPeriod);
    }

    /** 在游标处推进一根 K 线（只能传入当前及之前的 bar）。 */
    public void update(KlineBar bar, int index) {
        double close = bar.close();
        fast.next(close, emaFastPeriod);
        slow.next(close, emaSlowPeriod);
        adx = nextAdx(adx, bar, index);
        prevClose = close;
        prevHigh = bar.high();
        prevLow = bar.low();
    }

    public double emaFast() {
        return fast.value;
    }

    public double emaSlow() {
        return slow.value;
    }

    public double adx() {
        return adx;
    }

    /** 形态判定：ADX 低 且 快慢线重合 → GRID；ADX 高 且 快慢线分离 → MARTIN；其余 WAIT。 */
    public Regime regime() {
        double ef = fast.value;
        double es = slow.value;
        if (Double.isNaN(ef) || Double.isNaN(es) || Double.isNaN(adx)) {
            return Regime.WAIT;
        }
        double diffPct = Math.abs(ef - es) / Math.abs(es) * 100.0;
        if (adx <= adxGridMax && diffPct <= trendEpsilonPct) {
            return Regime.GRID;
        }
        if (adx >= adxTrendMin && diffPct > trendEpsilonPct) {
            return Regime.MARTIN;
        }
        return Regime.WAIT;
    }

    /** EMA 增量状态（前 N 个 SMA 播种，之后按 k=2/(N+1) 递推；TA-Lib 约定）。 */
    private static final class EmaState {
        private double sum;
        private int count;
        private double value = Double.NaN;

        void next(double v, int period) {
            if (count < period) {
                sum += v;
                count++;
                if (count == period) {
                    value = sum / period;
                }
                return;
            }
            double k = 2.0 / (period + 1.0);
            value = v * k + value * (1 - k);
        }
    }

    /**
     * ADX（Wilder 平滑）：与 {@code IndicatorService.adx} 口径一致——+DI/−DI 自索引 period 起有效，
     * ADX 自索引 2·period−1 起有效（先对 period 个 DX 取均值播种，之后 Wilder 递推）。
     */
    private double nextAdx(double current, KlineBar bar, int index) {
        if (index == 0 || Double.isNaN(prevHigh)) {
            return current;
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
            return current;
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
            return Double.NaN;
        }
        return (current * (adxPeriod - 1) + dx) / adxPeriod;
    }
}
