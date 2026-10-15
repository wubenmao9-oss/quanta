package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 马丁/网格<b>机械手数算法</b>（纯函数、无状态、可单测）：与《策略.txt》结果表口径一致。
 *
 * <p>约定：{@code point} 为最小价格变动（GOLD=0.01），{@code slPoints}/{@code tpPoints}/
 * {@code addLegDistancePoints} 均为「project-points」，{@code 100 points × point = 1.00 USD}。
 *
 * <p><b>止盈与腿止损解耦</b>：{@code legSl} 的距离是「灾难兜底」（只为满足 R5 的 SL_REQUIRED），
 * 不参与止盈计算；整轮止盈距离由独立的 {@code tpPoints} 决定，避免放宽兜底 SL 时把止盈目标一并拖远。
 *
 * <p><b>不写死原则</b>：全部参数由调用方传入（来自集中配置），本类不含魔法数字。
 */
public final class MartinMath {

    private MartinMath() {
    }

    /**
     * 基准手数：{@code floor(equity × baseLotPct/100 / step) × step}，并夹在
     * {@code [volumeMin, maxVolume / multiplier^(maxLevels-1)]} 内。
     *
     * <p>上界封顶是关键：保证第 {@code maxLevels} 腿手数不超过 {@code maxVolume}，
     * 任意账户规模下 R2 恒可满足。
     */
    public static double baseVolume(double equity, double baseLotPct, double volumeStep,
                                    double volumeMin, double maxVolume, double multiplier, int maxLevels) {
        double step = volumeStep <= 0 ? 0.01 : volumeStep;
        double raw = equity * baseLotPct / 100.0;
        double floored = Math.floor(raw / step + 1e-9) * step;
        double cap = maxVolume / Math.pow(multiplier, Math.max(0, maxLevels - 1));
        // 上界先向下取整到手数步长：否则 round2 会把 cap（如 0.125）向上舍入为 0.13，
        // 使第 maxLevels 腿 = 0.13×2^4 = 2.08 > maxVolume，破坏「第 maxLevels 腿 ≤ maxVolume」。
        double capFloored = Math.floor(cap / step + 1e-9) * step;
        double lo = Math.max(volumeMin, step);
        double hi = Math.max(lo, capFloored);
        double v = Math.min(Math.max(floored, lo), hi);
        return round2(v);
    }

    /**
     * 第 {@code level} 腿手数（1 起）= {@code baseVolume × multiplier^(level-1)}。
     *
     * <p>{@code multiplier = 1.0}（等手数）时每腿同手；{@code 2.0} 时为马丁倍投（0.1→0.2→0.4→0.8→1.6）。
     */
    public static double legVolume(double baseVolume, int level, double multiplier) {
        return round2(baseVolume * Math.pow(multiplier, Math.max(0, level - 1)));
    }

    /**
     * 单腿<b>灾难兜底</b>止损价 = 入场价 ∓ slPoints×point（多头取下方、空头取上方）。
     *
     * <p>该价位刻意放远（远大于补齐满腿所需的价格逆行距离），仅用于满足风控 R5「必须带 SL」
     * 与极端行情保护，正常马丁加仓过程中不应被触发。
     */
    public static double legSl(RiskSide side, double entryPrice, double slPoints, double point) {
        double d = slPoints * point;
        return side == RiskSide.BUY ? entryPrice - d : entryPrice + d;
    }

    /**
     * 整轮均价止盈价 = 加权均价 ± tpPoints×point×basketTargetRr（多头取上方、空头取下方）。
     *
     * <p>与 {@link #legSl} 的兜底距离<b>无关</b>：放宽兜底 SL 不会把止盈目标一起拖远。
     */
    public static double basketTp(RiskSide side, double avgEntry, double tpPoints, double point, double basketTargetRr) {
        double d = tpPoints * point * basketTargetRr;
        return side == RiskSide.BUY ? avgEntry + d : avgEntry - d;
    }

    /**
     * 逆势补腿触发价 = 上一腿入场价 ∓ addLegDistancePoints×point。
     * 引擎据此判定「浮亏是否已达补腿距离」。
     */
    public static double addTrigger(RiskSide side, double lastEntry, double addLegDistancePoints, double point) {
        double d = addLegDistancePoints * point;
        return side == RiskSide.BUY ? lastEntry - d : lastEntry + d;
    }

    /** 加权均价（按手数加权）；腿为空时返回 NaN。 */
    public static double avgEntry(java.util.List<BasketLeg> legs) {
        double vol = 0;
        double sum = 0;
        for (BasketLeg l : legs) {
            vol += l.volume();
            sum += l.volume() * l.entryPrice();
        }
        return vol == 0 ? Double.NaN : sum / vol;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
