package com.benmao.quanta.schedule.rule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.schedule.ScheduleProperties;
import com.benmao.quanta.schedule.TriggerContext;
import com.benmao.quanta.schedule.TriggerRule;
import com.benmao.quanta.schedule.TriggerSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T4 指标插件（最小实现之一）：<b>RSI + 布林带</b>。
 *
 * <p>命中条件（任一）：
 * <ul>
 *   <li>超买回落：RSI ≥ {@code rsi-overbought} 且收盘价触及布林上轨；</li>
 *   <li>超卖反弹：RSI ≤ {@code rsi-oversold} 且收盘价触及布林下轨。</li>
 * </ul>
 *
 * <p>默认关闭（T4 为插件源），需在 {@code quanta.schedule.trigger.switches.T4=true} 打开。
 */
@Component
public class RsiBollingerRule implements TriggerRule {

    private final IndicatorService indicator;
    private final IndicatorProperties indicatorProps;

    public RsiBollingerRule(IndicatorService indicator, IndicatorProperties indicatorProps) {
        this.indicator = indicator;
        this.indicatorProps = indicatorProps;
    }

    @Override
    public TriggerSource source() {
        return TriggerSource.T4;
    }

    @Override
    public String name() {
        return "RSI+布林带";
    }

    @Override
    public boolean matches(TriggerContext ctx) {
        List<KlineBar> bars = ctx.snapshot().bars();
        if (bars == null || bars.size() < 3) {
            return false;
        }
        double[] closes = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes[i] = bars.get(i).close();
        }
        int lastIndex = bars.size() - 1;
        double[] rsi = indicator.rsi(closes, indicatorProps.getRsiPeriod());
        IndicatorService.BollResult boll = indicator.boll(closes,
                indicatorProps.getBollPeriod(), indicatorProps.getBollK());
        double rsiValue = rsi[lastIndex];
        double upper = boll.upper()[lastIndex];
        double lower = boll.lower()[lastIndex];
        double close = closes[lastIndex];
        if (Double.isNaN(rsiValue) || Double.isNaN(upper) || Double.isNaN(lower)) {
            return false;
        }
        ScheduleProperties.IndicatorRule cfg = ctx.props().getTrigger().getIndicator();
        boolean overbought = rsiValue >= cfg.getRsiOverbought() && close >= upper;
        boolean oversold = rsiValue <= cfg.getRsiOversold() && close <= lower;
        return overbought || oversold;
    }
}
