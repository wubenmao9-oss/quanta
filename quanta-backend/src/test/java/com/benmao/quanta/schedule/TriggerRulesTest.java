package com.benmao.quanta.schedule;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.indicator.IndicatorParamValidator;
import com.benmao.quanta.indicator.IndicatorProperties;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.schedule.rule.RsiBollingerRule;
import com.benmao.quanta.schedule.rule.VolumeEngulfingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3/T4 触发插件单测（M7：插件化落地的两个最小实现）。
 */
class TriggerRulesTest {

    private final IndicatorProperties indicatorProps = new IndicatorProperties();
    private final IndicatorService indicator =
            new IndicatorService(new IndicatorParamValidator(indicatorProps));

    private ScheduleProperties props;

    @BeforeEach
    void setUp() {
        props = new ScheduleProperties();
        props.getTrigger().getPattern().setVolumeMaPeriod(3);
        props.getTrigger().getPattern().setVolumeSpikeMultiplier(1.5);
    }

    @Test
    @DisplayName("T3 放量+吞没：放量且阳包阴 → 命中；仅吞没不放量 → 不命中")
    void volumeEngulfing() {
        VolumeEngulfingRule rule = new VolumeEngulfingRule(indicator);

        assertTrue(rule.matches(ctx(engulfingBars(1000))));
        assertFalse(rule.matches(ctx(engulfingBars(100))));
    }

    @Test
    @DisplayName("T4 RSI+布林带：超卖触下轨 → 命中；单边上行不触上轨 → 不命中")
    void rsiBollinger() {
        RsiBollingerRule rule = new RsiBollingerRule(indicator, indicatorProps);

        assertTrue(rule.matches(ctx(dropBars())));
        assertFalse(rule.matches(ctx(risingBars())));
    }

    // ==================== 辅助 ====================

    private TriggerContext ctx(List<KlineBar> bars) {
        KlineBar last = bars.get(bars.size() - 1);
        MarketSnapshot snap = new MarketSnapshot("GOLD", "M5", LocalDateTime.of(2026, 10, 12, 16, 0),
                last.close(), last.close(), 16, last.close(), last.high(), last.low(), bars,
                Instant.parse("2026-10-12T08:00:00Z"));
        return new TriggerContext(snap, props);
    }

    /** 5 根 K 线，末根为阳包阴；成交量由参数控制。 */
    private static List<KlineBar> engulfingBars(long lastVolume) {
        return List.of(
                new KlineBar(1760000000L, 4200, 4201, 4197, 4198, 100),
                new KlineBar(1760000300L, 4198, 4203, 4197, 4202, 100),
                new KlineBar(1760000600L, 4202, 4203, 4199, 4200, 100),
                new KlineBar(1760000900L, 4200, 4201, 4194, 4195, 100),
                new KlineBar(1760001200L, 4194, 4202, 4194, 4201, lastVolume));
    }

    /** 25 根横盘 4200 后急跌一根至 4150：RSI 极低且收盘击穿布林下轨。 */
    private static List<KlineBar> dropBars() {
        List<KlineBar> bars = new ArrayList<>();
        long t = 1760000000L;
        for (int i = 0; i < 25; i++) {
            bars.add(new KlineBar(t + i * 300L, 4200, 4200, 4200, 4200, 100));
        }
        bars.add(new KlineBar(t + 25 * 300L, 4200, 4200, 4145, 4150, 100));
        return bars;
    }

    /** 26 根单边上行 4200→4225：RSI 高但收盘未触及布林上轨。 */
    private static List<KlineBar> risingBars() {
        List<KlineBar> bars = new ArrayList<>();
        long t = 1760000000L;
        for (int i = 0; i < 26; i++) {
            double close = 4200 + i;
            bars.add(new KlineBar(t + i * 300L, close - 1, close + 1, close - 2, close, 100));
        }
        return bars;
    }
}
