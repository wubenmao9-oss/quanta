package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

import java.time.LocalDateTime;

/**
 * 一笔已平仓的回测交易明细（对应 backtest_trade 一行）。
 *
 * @param profit 价差盈亏（不含 swap，与实盘 MT5 的 profit 口径一致）
 * @param swap   隔夜费（负值 = 成本）
 * @param cycleNo    所属轮编号（单仓策略为 0）
 * @param level      该腿在轮内的序号（单仓策略为 0）
 * @param engineKind 引擎类型（{@code SINGLE_LEG}/{@code MARTIN}/{@code GRID}/{@code LEGACY}）
 */
public record ClosedTrade(int openIndex,
                          int closeIndex,
                          LocalDateTime openTime,
                          LocalDateTime closeTime,
                          RiskSide side,
                          double volume,
                          double openPrice,
                          double closePrice,
                          double sl,
                          double tp,
                          double profit,
                          double swap,
                          int holdSec,
                          String exitReason,
                          int cycleNo,
                          int level,
                          String engineKind) {

    /**
     * 兼容构造器：旧 14 参调用点（单仓引擎）行为不变，轮/腿置 0、引擎类型置 {@code LEGACY}。
     */
    public ClosedTrade(int openIndex, int closeIndex, LocalDateTime openTime, LocalDateTime closeTime,
                       RiskSide side, double volume, double openPrice, double closePrice, double sl, double tp,
                       double profit, double swap, int holdSec, String exitReason) {
        this(openIndex, closeIndex, openTime, closeTime, side, volume, openPrice, closePrice, sl, tp,
                profit, swap, holdSec, exitReason, 0, 0, "LEGACY");
    }

    /** 净盈亏（计入 swap），用于胜率 / 期望 / 回撤等统计。 */
    public double netProfit() {
        return profit + swap;
    }
}
