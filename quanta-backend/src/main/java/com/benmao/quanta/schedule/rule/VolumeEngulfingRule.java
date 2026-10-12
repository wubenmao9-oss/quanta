package com.benmao.quanta.schedule.rule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.schedule.ScheduleProperties;
import com.benmao.quanta.schedule.TriggerContext;
import com.benmao.quanta.schedule.TriggerRule;
import com.benmao.quanta.schedule.TriggerSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T3 形态插件（最小实现之一）：<b>放量 + 吞没</b>。
 *
 * <p>命中条件（同时满足）：
 * <ol>
 *   <li>最新 K 线成交量 &gt; 均量 × {@code volume-spike-multiplier}（放量）；</li>
 *   <li>最新一根对前一根构成看涨/看跌吞没（实体完全包裹前一根实体）。</li>
 * </ol>
 *
 * <p>默认关闭（T3 为插件源），需在 {@code quanta.schedule.trigger.switches.T3=true} 打开。
 */
@Component
public class VolumeEngulfingRule implements TriggerRule {

    private final IndicatorService indicator;

    public VolumeEngulfingRule(IndicatorService indicator) {
        this.indicator = indicator;
    }

    @Override
    public TriggerSource source() {
        return TriggerSource.T3;
    }

    @Override
    public String name() {
        return "放量+吞没";
    }

    @Override
    public boolean matches(TriggerContext ctx) {
        List<KlineBar> bars = ctx.snapshot().bars();
        if (bars == null || bars.size() < 3) {
            return false;
        }
        ScheduleProperties.Pattern cfg = ctx.props().getTrigger().getPattern();
        int lastIndex = bars.size() - 1;
        double[] volMa = indicator.volumeMa(bars, cfg.getVolumeMaPeriod());
        double avg = volMa[lastIndex];
        if (Double.isNaN(avg) || avg <= 0) {
            return false;
        }
        KlineBar prev = bars.get(lastIndex - 1);
        KlineBar cur = bars.get(lastIndex);
        boolean spike = cur.volume() > avg * cfg.getVolumeSpikeMultiplier();
        return spike && engulfing(prev, cur);
    }

    /** 看涨吞没（阳包阴）或看跌吞没（阴包阳）。 */
    private static boolean engulfing(KlineBar prev, KlineBar cur) {
        boolean bullish = cur.close() > cur.open() && prev.close() < prev.open()
                && cur.open() <= prev.close() && cur.close() >= prev.open();
        boolean bearish = cur.close() < cur.open() && prev.close() > prev.open()
                && cur.open() >= prev.close() && cur.close() <= prev.open();
        return bullish || bearish;
    }
}
