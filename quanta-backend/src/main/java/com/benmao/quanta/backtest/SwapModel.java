package com.benmao.quanta.backtest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * 隔夜费（swap）共享口径：由 {@link ReplayEngine} 抽出，供单仓引擎与多腿引擎复用同一套算法，
 * 避免两处口径分叉。
 *
 * <p>算法与 {@code ReplayEngine} 原实现<b>逐行一致</b>（持仓跨越每日结算点计一次，可配周三三倍）。
 */
public final class SwapModel {

    private final BacktestProperties props;
    private final ZoneId zone;

    public SwapModel(BacktestProperties props, ZoneId zone) {
        this.props = props;
        this.zone = zone;
    }

    /** 隔夜费：持仓跨越每日结算点则计一次，周三（可配）计三倍（固定日均近似）。 */
    public double swapFor(long openEpoch, long closeEpoch, double volume) {
        if (!props.isSwapOn() || props.getSwapPerLotPerDay() == 0) {
            return 0;
        }
        DayOfWeek triple = parseWeekday(props.getSwapTripleWeekday());
        int hour = Math.min(23, Math.max(0, props.getSettlementHour()));
        LocalDate openDate = toLocalDate(openEpoch);
        LocalDate closeDate = toLocalDate(closeEpoch);
        double total = 0;
        for (LocalDate d = openDate; !d.isAfter(closeDate); d = d.plusDays(1)) {
            long settlement = d.atTime(hour, 0).atZone(zone).toEpochSecond();
            if (settlement > openEpoch && settlement <= closeEpoch) {
                double factor = (triple != null && d.getDayOfWeek() == triple) ? 3.0 : 1.0;
                total += props.getSwapPerLotPerDay() * volume * factor;
            }
        }
        return total;
    }

    private LocalDate toLocalDate(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate();
    }

    private static DayOfWeek parseWeekday(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
