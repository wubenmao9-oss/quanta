package com.benmao.quanta.indicator;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标层（设计 §10）：本地计算 EMA/SMA/RSI/MACD/BOLL/ATR/成交量，并支持「指标组」联合判断。
 *
 * <p>约定：
 * <ul>
 *   <li>所有结果数组与输入 K 线<b>等长</b>，预热期（样本不足）为 {@link Double#NaN}；</li>
 *   <li>公式沿用 TA-Lib 约定（EMA 用前 N 个 SMA 播种；RSI/ATR 用 Wilder 平滑；BOLL 用总体标准差），
 *       与独立参考实现（numpy）逐点比对；</li>
 *   <li>参数先经 {@link IndicatorParamValidator} 校验，非法即拒并回执合法边界（防幻觉）。</li>
 * </ul>
 *
 * <p>本服务为<b>纯计算</b>、无外部依赖，实盘（M7/M10）与回测（M8）共用同一套实现。
 */
@Service
public class IndicatorService {

    private final IndicatorParamValidator validator;

    public IndicatorService(IndicatorParamValidator validator) {
        this.validator = validator;
    }

    // ==================== 对外：定型计算（含参数校验） ====================

    /** 解析并校验参数，返回该指标定型后的参数表（供接口回显）。 */
    public Map<String, Object> resolvedParams(IndicatorType type, Map<String, Object> params) {
        return validator.resolved(type, params);
    }

    /** 按指标类型计算，返回「字段名 → 序列」；参数非法会抛 {@link BizException}。 */
    public Map<String, double[]> compute(IndicatorType type, Map<String, Object> params, List<KlineBar> bars) {
        if (bars == null || bars.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "K 线为空，无法计算 " + type, 400);
        }
        Map<String, Object> p = validator.resolved(type, params);
        Map<String, double[]> out = new LinkedHashMap<>();
        switch (type) {
            case SMA -> out.put("value", sma(closes(bars), (int) p.get("period")));
            case EMA -> out.put("value", ema(closes(bars), (int) p.get("period")));
            case RSI -> out.put("value", rsi(closes(bars), (int) p.get("period")));
            case ATR -> out.put("value", atr(bars, (int) p.get("period")));
            case VOLUME -> {
                out.put("volume", volume(bars));
                out.put("ma", volumeMa(bars, (int) p.get("period")));
            }
            case BOLL -> {
                BollResult r = boll(closes(bars), (int) p.get("period"), (double) p.get("k"));
                out.put("mid", r.mid());
                out.put("upper", r.upper());
                out.put("lower", r.lower());
            }
            case MACD -> {
                MacdResult r = macd(closes(bars), (int) p.get("fast"), (int) p.get("slow"), (int) p.get("signal"));
                out.put("dif", r.dif());
                out.put("dea", r.dea());
                out.put("hist", r.hist());
            }
            case ADX -> {
                AdxResult r = adx(bars, (int) p.get("period"));
                out.put("plusDi", r.plusDi());
                out.put("minusDi", r.minusDi());
                out.put("adx", r.adx());
            }
            case AROON -> {
                AroonResult r = aroon(bars, (int) p.get("period"));
                out.put("up", r.up());
                out.put("down", r.down());
                out.put("osc", r.osc());
            }
            case STOCH -> {
                StochResult r = stoch(bars, (int) p.get("period"),
                        (int) p.get("kSmooth"), (int) p.get("dSmooth"));
                out.put("k", r.k());
                out.put("d", r.d());
                out.put("j", r.j());
            }
            case CCI -> out.put("value", cci(bars, (int) p.get("period")));
            case WILLR -> out.put("value", willr(bars, (int) p.get("period")));
            case ROC -> out.put("value", roc(closes(bars), (int) p.get("period")));
            case DONCHIAN -> {
                DonchianResult r = donchian(bars, (int) p.get("period"));
                out.put("upper", r.upper());
                out.put("mid", r.mid());
                out.put("lower", r.lower());
            }
            case STDDEV -> out.put("value", stddev(closes(bars), (int) p.get("period")));
            case CHOP -> out.put("value", choppiness(bars, (int) p.get("period")));
            case ADR -> out.put("value", adr(bars, (int) p.get("period")));
            case KELTNER -> {
                KeltnerResult r = keltner(bars, (int) p.get("period"), (double) p.get("k"));
                out.put("mid", r.mid());
                out.put("upper", r.upper());
                out.put("lower", r.lower());
            }
            case SUPERTREND -> {
                SupertrendResult r = supertrend(bars, (int) p.get("period"), (double) p.get("k"));
                out.put("line", r.line());
                out.put("direction", r.direction());
            }
            case BBW -> out.put("value",
                    bbWidth(closes(bars), (int) p.get("period"), (double) p.get("k")));
            case BBP -> out.put("value",
                    bbPercent(closes(bars), (int) p.get("period"), (double) p.get("k")));
        }
        return out;
    }

    // ==================== 指标原语（数组入参，纯函数） ====================

    /** 简单移动平均。 */
    public double[] sma(double[] values, int period) {
        double[] out = nanArray(values.length);
        if (values.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    /** 指数移动平均：前 {@code period} 个的 SMA 播种（索引 period-1），之后按 k=2/(period+1) 递推。 */
    public double[] ema(double[] values, int period) {
        double[] out = nanArray(values.length);
        if (values.length < period) {
            return out;
        }
        double k = 2.0 / (period + 1.0);
        double seed = 0;
        for (int i = 0; i < period; i++) {
            seed += values[i];
        }
        out[period - 1] = seed / period;
        for (int i = period; i < values.length; i++) {
            out[i] = values[i] * k + out[i - 1] * (1 - k);
        }
        return out;
    }

    /** 相对强弱指标（Wilder 平滑），首个有效值在索引 period。 */
    public double[] rsi(double[] values, int period) {
        double[] out = nanArray(values.length);
        if (values.length <= period) {
            return out;
        }
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double d = values[i] - values[i - 1];
            if (d > 0) {
                avgGain += d;
            } else {
                avgLoss -= d;
            }
        }
        avgGain /= period;
        avgLoss /= period;
        out[period] = rsiValue(avgGain, avgLoss);
        for (int i = period + 1; i < values.length; i++) {
            double d = values[i] - values[i - 1];
            double gain = d > 0 ? d : 0;
            double loss = d < 0 ? -d : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    /** MACD：DIF = EMA(fast) − EMA(slow)；DEA = EMA(DIF, signal)；HIST = (DIF − DEA)。 */
    public MacdResult macd(double[] values, int fast, int slow, int signal) {
        double[] emaFast = ema(values, fast);
        double[] emaSlow = ema(values, slow);
        double[] dif = nanArray(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(emaFast[i]) && !Double.isNaN(emaSlow[i])) {
                dif[i] = emaFast[i] - emaSlow[i];
            }
        }
        double[] dea = nanArray(values.length);
        int first = firstValid(dif);
        if (first >= 0) {
            int len = values.length - first;
            if (len >= signal) {
                double[] seq = Arrays.copyOfRange(dif, first, values.length);
                double[] sub = ema(seq, signal);
                for (int j = 0; j < len; j++) {
                    if (!Double.isNaN(sub[j])) {
                        dea[first + j] = sub[j];
                    }
                }
            }
        }
        double[] hist = nanArray(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(dif[i]) && !Double.isNaN(dea[i])) {
                hist[i] = dif[i] - dea[i];
            }
        }
        return new MacdResult(dif, dea, hist);
    }

    /** 布林带：中轨 = SMA(period)，上下轨 = 中轨 ± k × 总体标准差。 */
    public BollResult boll(double[] values, int period, double k) {
        double[] mid = sma(values, period);
        double[] upper = nanArray(values.length);
        double[] lower = nanArray(values.length);
        for (int i = period - 1; i < values.length; i++) {
            double mean = mid[i];
            double sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double d = values[j] - mean;
                sumSq += d * d;
            }
            double sd = Math.sqrt(sumSq / period);
            upper[i] = mean + k * sd;
            lower[i] = mean - k * sd;
        }
        return new BollResult(mid, upper, lower);
    }

    /** 平均真实波幅（Wilder 平滑），TR 首根用 high−low。 */
    public double[] atr(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] out = nanArray(m);
        if (m == 0) {
            return out;
        }
        double[] tr = new double[m];
        KlineBar first = bars.get(0);
        tr[0] = first.high() - first.low();
        for (int i = 1; i < m; i++) {
            KlineBar b = bars.get(i);
            KlineBar prev = bars.get(i - 1);
            tr[i] = Math.max(b.high() - b.low(),
                    Math.max(Math.abs(b.high() - prev.close()), Math.abs(b.low() - prev.close())));
        }
        if (m < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        out[period - 1] = sum / period;
        for (int i = period; i < m; i++) {
            out[i] = (out[i - 1] * (period - 1) + tr[i]) / period;
        }
        return out;
    }

    /** 成交量原始序列。 */
    public double[] volume(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).volume();
        }
        return out;
    }

    /** 成交量均线。 */
    public double[] volumeMa(List<KlineBar> bars, int period) {
        return sma(volume(bars), period);
    }

    // ==================== 指标原语（多样化扩展，仍为纯函数） ====================

    /** 动向指标 DMI/ADX（Wilder 平滑）：+DI / −DI 在索引 period 起有效，ADX 在索引 2·period−1 起有效。 */
    public AdxResult adx(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] plusDi = nanArray(m);
        double[] minusDi = nanArray(m);
        double[] adx = nanArray(m);
        if (m <= period) {
            return new AdxResult(plusDi, minusDi, adx);
        }
        double[] tr = trueRange(bars);
        double[] pdm = new double[m];
        double[] ndm = new double[m];
        for (int i = 1; i < m; i++) {
            KlineBar b = bars.get(i);
            KlineBar prev = bars.get(i - 1);
            double up = b.high() - prev.high();
            double dn = prev.low() - b.low();
            pdm[i] = (up > dn && up > 0) ? up : 0;
            ndm[i] = (dn > up && dn > 0) ? dn : 0;
        }
        double sTr = 0;
        double sPdm = 0;
        double sNdm = 0;
        for (int i = 1; i <= period; i++) {
            sTr += tr[i];
            sPdm += pdm[i];
            sNdm += ndm[i];
        }
        double[] dx = nanArray(m);
        for (int i = period; i < m; i++) {
            if (i > period) {
                sTr = sTr - sTr / period + tr[i];
                sPdm = sPdm - sPdm / period + pdm[i];
                sNdm = sNdm - sNdm / period + ndm[i];
            }
            double pdi = sTr == 0 ? 0 : 100.0 * sPdm / sTr;
            double ndi = sTr == 0 ? 0 : 100.0 * sNdm / sTr;
            plusDi[i] = pdi;
            minusDi[i] = ndi;
            double sum = pdi + ndi;
            dx[i] = sum == 0 ? 0 : 100.0 * Math.abs(pdi - ndi) / sum;
        }
        int first = 2 * period - 1;
        if (first < m) {
            double sum = 0;
            for (int i = period; i <= first; i++) {
                sum += dx[i];
            }
            adx[first] = sum / period;
            for (int i = first + 1; i < m; i++) {
                adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period;
            }
        }
        return new AdxResult(plusDi, minusDi, adx);
    }

    /** 阿隆指标：窗口 period+1 内最高/最低价距当前的时间占比；并列极值取<b>最近</b>一根。 */
    public AroonResult aroon(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] up = nanArray(m);
        double[] down = nanArray(m);
        double[] osc = nanArray(m);
        for (int i = period; i < m; i++) {
            int hiIdx = i - period;
            int loIdx = i - period;
            double hi = bars.get(i - period).high();
            double lo = bars.get(i - period).low();
            for (int j = i - period + 1; j <= i; j++) {
                double h = bars.get(j).high();
                double l = bars.get(j).low();
                if (h >= hi) {
                    hi = h;
                    hiIdx = j;
                }
                if (l <= lo) {
                    lo = l;
                    loIdx = j;
                }
            }
            up[i] = 100.0 * (period - (i - hiIdx)) / period;
            down[i] = 100.0 * (period - (i - loIdx)) / period;
            osc[i] = up[i] - down[i];
        }
        return new AroonResult(up, down, osc);
    }

    /** 随机指标 KDJ：RSV 经 kSmooth/dSmooth 的 1/n 指数平滑（初始 50），J = 3K − 2D。 */
    public StochResult stoch(List<KlineBar> bars, int period, int kSmooth, int dSmooth) {
        int m = bars.size();
        double[] k = nanArray(m);
        double[] d = nanArray(m);
        double[] j = nanArray(m);
        if (m < period) {
            return new StochResult(k, d, j);
        }
        double[] rsv = nanArray(m);
        for (int i = period - 1; i < m; i++) {
            double hh = bars.get(i - period + 1).high();
            double ll = bars.get(i - period + 1).low();
            for (int t = i - period + 2; t <= i; t++) {
                hh = Math.max(hh, bars.get(t).high());
                ll = Math.min(ll, bars.get(t).low());
            }
            double range = hh - ll;
            double c = bars.get(i).close();
            rsv[i] = range == 0 ? 50.0 : (c - ll) / range * 100.0;
        }
        double aK = 1.0 / kSmooth;
        double aD = 1.0 / dSmooth;
        double kPrev = 50.0;
        double dPrev = 50.0;
        for (int i = period - 1; i < m; i++) {
            k[i] = aK * rsv[i] + (1 - aK) * kPrev;
            d[i] = aD * k[i] + (1 - aD) * dPrev;
            j[i] = 3 * k[i] - 2 * d[i];
            kPrev = k[i];
            dPrev = d[i];
        }
        return new StochResult(k, d, j);
    }

    /** 商品通道指数：CCI = (TP − SMA(TP)) / (0.015 × 平均绝对偏差)。 */
    public double[] cci(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] out = nanArray(m);
        double[] tp = new double[m];
        for (int i = 0; i < m; i++) {
            KlineBar b = bars.get(i);
            tp[i] = (b.high() + b.low() + b.close()) / 3.0;
        }
        for (int i = period - 1; i < m; i++) {
            double mean = 0;
            for (int t = i - period + 1; t <= i; t++) {
                mean += tp[t];
            }
            mean /= period;
            double dev = 0;
            for (int t = i - period + 1; t <= i; t++) {
                dev += Math.abs(tp[t] - mean);
            }
            dev /= period;
            out[i] = dev == 0 ? 0 : (tp[i] - mean) / (0.015 * dev);
        }
        return out;
    }

    /** 威廉指标：WILLR = −100 × (HH − close) / (HH − LL)，范围 [−100, 0]。 */
    public double[] willr(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] out = nanArray(m);
        for (int i = period - 1; i < m; i++) {
            double hh = bars.get(i - period + 1).high();
            double ll = bars.get(i - period + 1).low();
            for (int t = i - period + 2; t <= i; t++) {
                hh = Math.max(hh, bars.get(t).high());
                ll = Math.min(ll, bars.get(t).low());
            }
            double range = hh - ll;
            out[i] = range == 0 ? 0 : -100.0 * (hh - bars.get(i).close()) / range;
        }
        return out;
    }

    /** 变动率：ROC = (close − close[−period]) / close[−period] × 100。 */
    public double[] roc(double[] values, int period) {
        double[] out = nanArray(values.length);
        for (int i = period; i < values.length; i++) {
            double prev = values[i - period];
            out[i] = prev == 0 ? 0 : (values[i] - prev) / prev * 100.0;
        }
        return out;
    }

    /** 肯特纳通道：中轨 = EMA(close)，上下轨 = 中轨 ± k × ATR(Wilder)。 */
    public KeltnerResult keltner(List<KlineBar> bars, int period, double k) {
        int m = bars.size();
        double[] mid = ema(closes(bars), period);
        double[] a = atr(bars, period);
        double[] upper = nanArray(m);
        double[] lower = nanArray(m);
        for (int i = 0; i < m; i++) {
            if (!Double.isNaN(mid[i]) && !Double.isNaN(a[i])) {
                upper[i] = mid[i] + k * a[i];
                lower[i] = mid[i] - k * a[i];
            }
        }
        return new KeltnerResult(mid, upper, lower);
    }

    /** 唐奇安通道：N 周期最高价（上轨）/最低价（下轨），中轨为其均值。 */
    public DonchianResult donchian(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] upper = nanArray(m);
        double[] mid = nanArray(m);
        double[] lower = nanArray(m);
        for (int i = period - 1; i < m; i++) {
            double hh = bars.get(i - period + 1).high();
            double ll = bars.get(i - period + 1).low();
            for (int t = i - period + 2; t <= i; t++) {
                hh = Math.max(hh, bars.get(t).high());
                ll = Math.min(ll, bars.get(t).low());
            }
            upper[i] = hh;
            lower[i] = ll;
            mid[i] = (hh + ll) / 2.0;
        }
        return new DonchianResult(upper, mid, lower);
    }

    /** 布林带宽度：BBW = (上轨 − 下轨) / 中轨 × 100。 */
    public double[] bbWidth(double[] values, int period, double k) {
        BollResult b = boll(values, period, k);
        double[] out = nanArray(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(b.mid()[i])) {
                out[i] = b.mid()[i] == 0 ? 0 : (b.upper()[i] - b.lower()[i]) / b.mid()[i] * 100.0;
            }
        }
        return out;
    }

    /** 布林带百分比：%B = (close − 下轨) / (上轨 − 下轨)，1 表示贴上轨、0 表示贴下轨。 */
    public double[] bbPercent(double[] values, int period, double k) {
        BollResult b = boll(values, period, k);
        double[] out = nanArray(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(b.upper()[i])) {
                double width = b.upper()[i] - b.lower()[i];
                out[i] = width == 0 ? 0.5 : (values[i] - b.lower()[i]) / width;
            }
        }
        return out;
    }

    /** 滚动标准差（总体标准差，除以 N）。 */
    public double[] stddev(double[] values, int period) {
        double[] out = nanArray(values.length);
        for (int i = period - 1; i < values.length; i++) {
            double mean = 0;
            for (int t = i - period + 1; t <= i; t++) {
                mean += values[t];
            }
            mean /= period;
            double sq = 0;
            for (int t = i - period + 1; t <= i; t++) {
                double df = values[t] - mean;
                sq += df * df;
            }
            out[i] = Math.sqrt(sq / period);
        }
        return out;
    }

    /** 震荡指数：CHOP = 100 × log10(Σ TR / (HH − LL)) / log10(period)，越高越盘整。 */
    public double[] choppiness(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] out = nanArray(m);
        if (period < 2) {
            return out;
        }
        double[] tr = trueRange(bars);
        for (int i = period - 1; i < m; i++) {
            double sumTr = 0;
            double hh = bars.get(i - period + 1).high();
            double ll = bars.get(i - period + 1).low();
            for (int t = i - period + 1; t <= i; t++) {
                sumTr += tr[t];
                hh = Math.max(hh, bars.get(t).high());
                ll = Math.min(ll, bars.get(t).low());
            }
            double range = hh - ll;
            if (range > 0 && sumTr > 0) {
                out[i] = 100.0 * Math.log10(sumTr / range) / Math.log10(period);
            }
        }
        return out;
    }

    /** 日均波幅：N 周期内 (high − low) 的算术平均。 */
    public double[] adr(List<KlineBar> bars, int period) {
        int m = bars.size();
        double[] out = nanArray(m);
        for (int i = period - 1; i < m; i++) {
            double sum = 0;
            for (int t = i - period + 1; t <= i; t++) {
                sum += bars.get(t).high() - bars.get(t).low();
            }
            out[i] = sum / period;
        }
        return out;
    }

    /** 超级趋势：基于 ATR 的通道；{@code direction}=1 为多头（落在下轨）、−1 为空头（落在上轨）。 */
    public SupertrendResult supertrend(List<KlineBar> bars, int period, double k) {
        int m = bars.size();
        double[] line = nanArray(m);
        double[] direction = nanArray(m);
        double[] a = atr(bars, period);
        int start = firstValid(a);
        if (start < 0) {
            return new SupertrendResult(line, direction);
        }
        double finalUpper = 0;
        double finalLower = 0;
        double trend = 0;
        for (int i = start; i < m; i++) {
            KlineBar b = bars.get(i);
            double hl2 = (b.high() + b.low()) / 2.0;
            double basicUpper = hl2 + k * a[i];
            double basicLower = hl2 - k * a[i];
            if (i == start) {
                finalUpper = basicUpper;
                finalLower = basicLower;
                trend = b.close() >= hl2 ? 1 : -1;
            } else {
                double prevClose = bars.get(i - 1).close();
                double prevUpper = finalUpper;
                double prevLower = finalLower;
                finalUpper = (basicUpper < prevUpper || prevClose > prevUpper) ? basicUpper : prevUpper;
                finalLower = (basicLower > prevLower || prevClose < prevLower) ? basicLower : prevLower;
                if (b.close() > prevUpper) {
                    trend = 1;
                } else if (b.close() < prevLower) {
                    trend = -1;
                }
            }
            line[i] = trend == 1 ? finalLower : finalUpper;
            direction[i] = trend;
        }
        return new SupertrendResult(line, direction);
    }

    // ==================== 指标组 ====================

    /** 在最后一根 K 线上评估指标组。 */
    public IndicatorGroupSpec.Evaluation evaluateGroup(List<KlineBar> bars, IndicatorGroupSpec spec) {
        if (bars == null || bars.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "K 线为空，无法评估指标组", 400);
        }
        return evaluateGroupAt(bars, spec, bars.size() - 1);
    }

    /** 在指定位置评估指标组。 */
    public IndicatorGroupSpec.Evaluation evaluateGroupAt(List<KlineBar> bars,
                                                         IndicatorGroupSpec spec,
                                                         int index) {
        if (bars == null || bars.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "K 线为空，无法评估指标组", 400);
        }
        if (spec == null || spec.rules() == null || spec.rules().isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "指标组至少需要一条规则", 400);
        }
        if (index < 0 || index >= bars.size()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "评估位置越界：" + index + "，合法范围 [0, " + (bars.size() - 1) + "]", 400);
        }
        boolean and = spec.logic() != IndicatorGroupSpec.Logic.OR;
        boolean matched = and;
        Map<String, Boolean> perRule = new LinkedHashMap<>();
        int seq = 1;
        for (IndicatorGroupSpec.Rule rule : spec.rules()) {
            String key = (rule.name() == null || rule.name().isBlank()) ? "rule" + seq : rule.name();
            boolean hit = evaluateRule(bars, rule, index);
            perRule.put(key, hit);
            matched = and ? (matched && hit) : (matched || hit);
            seq++;
        }
        return new IndicatorGroupSpec.Evaluation(matched, perRule);
    }

    private boolean evaluateRule(List<KlineBar> bars, IndicatorGroupSpec.Rule rule, int index) {
        if (rule == null || rule.op() == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "指标组规则缺少比较运算符", 400);
        }
        if (rule.op() == IndicatorGroupSpec.Op.CROSS_UP || rule.op() == IndicatorGroupSpec.Op.CROSS_DOWN) {
            if (index == 0) {
                return false;
            }
            double l0 = operandValue(bars, rule.lhs(), index - 1);
            double r0 = operandValue(bars, rule.rhs(), index - 1);
            double l1 = operandValue(bars, rule.lhs(), index);
            double r1 = operandValue(bars, rule.rhs(), index);
            if (Double.isNaN(l0) || Double.isNaN(r0) || Double.isNaN(l1) || Double.isNaN(r1)) {
                return false;
            }
            return rule.op() == IndicatorGroupSpec.Op.CROSS_UP ? (l0 <= r0 && l1 > r1) : (l0 >= r0 && l1 < r1);
        }
        double l = operandValue(bars, rule.lhs(), index);
        double r = operandValue(bars, rule.rhs(), index);
        if (Double.isNaN(l) || Double.isNaN(r)) {
            return false;
        }
        return switch (rule.op()) {
            case GT -> l > r;
            case LT -> l < r;
            case GTE -> l >= r;
            case LTE -> l <= r;
            default -> false;
        };
    }

    private double operandValue(List<KlineBar> bars, IndicatorGroupSpec.Operand operand, int index) {
        if (operand == null || operand.kind() == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "指标组规则缺少操作数", 400);
        }
        return switch (operand.kind()) {
            case CONST -> operand.constant() == null ? Double.NaN : operand.constant();
            case PRICE -> priceOf(bars.get(index),
                    operand.priceField() == null ? IndicatorGroupSpec.PriceField.CLOSE : operand.priceField());
            case INDICATOR -> indicatorValue(bars, operand.ref(), index);
        };
    }

    private double indicatorValue(List<KlineBar> bars, IndicatorGroupSpec.Ref ref, int index) {
        if (ref == null || ref.indicator() == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "指标组规则缺少指标名", 400);
        }
        Map<String, double[]> series = compute(ref.indicator(), ref.params(), bars);
        String field = (ref.field() == null || ref.field().isBlank())
                ? ref.indicator().defaultField() : ref.field();
        double[] arr = series.get(field);
        if (arr == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "指标 " + ref.indicator() + " 不支持输出字段：" + field + "，可选：" + series.keySet(), 400);
        }
        return arr[index];
    }

    // ==================== 内部工具 ====================

    private static double[] closes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).close();
        }
        return out;
    }

    private static double priceOf(KlineBar bar, IndicatorGroupSpec.PriceField field) {
        return switch (field) {
            case OPEN -> bar.open();
            case HIGH -> bar.high();
            case LOW -> bar.low();
            case CLOSE -> bar.close();
        };
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        double rs = avgLoss == 0 ? Double.POSITIVE_INFINITY : avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    private static double[] nanArray(int length) {
        double[] out = new double[length];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    private static int firstValid(double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 真实波幅序列（TR[0] 用 high−low）。 */
    private static double[] trueRange(List<KlineBar> bars) {
        int m = bars.size();
        double[] tr = new double[m];
        if (m == 0) {
            return tr;
        }
        KlineBar first = bars.get(0);
        tr[0] = first.high() - first.low();
        for (int i = 1; i < m; i++) {
            KlineBar b = bars.get(i);
            KlineBar prev = bars.get(i - 1);
            tr[i] = Math.max(b.high() - b.low(),
                    Math.max(Math.abs(b.high() - prev.close()), Math.abs(b.low() - prev.close())));
        }
        return tr;
    }

    /** MACD 三线。 */
    public record MacdResult(double[] dif, double[] dea, double[] hist) {}

    /** 布林带三轨。 */
    public record BollResult(double[] mid, double[] upper, double[] lower) {}

    /** 动向指标三线。 */
    public record AdxResult(double[] plusDi, double[] minusDi, double[] adx) {}

    /** 阿隆指标三线。 */
    public record AroonResult(double[] up, double[] down, double[] osc) {}

    /** 随机指标 KDJ 三线。 */
    public record StochResult(double[] k, double[] d, double[] j) {}

    /** 肯特纳通道三轨。 */
    public record KeltnerResult(double[] mid, double[] upper, double[] lower) {}

    /** 唐奇安通道三轨。 */
    public record DonchianResult(double[] upper, double[] mid, double[] lower) {}

    /** 超级趋势：趋势线 + 方向（1 多头 / −1 空头）。 */
    public record SupertrendResult(double[] line, double[] direction) {}
}