package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

import java.time.LocalDateTime;

/**
 * 一笔已平仓的回测交易明细（对应 backtest_trade 一行）。
 *
 * @param profit 价差盈亏（不含 swap，与实盘 MT5 的 profit 口径一致）
 * @param swap   隔夜费（负值 = 成本）
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
                          String exitReason) {

    /** 净盈亏（计入 swap），用于胜率 / 期望 / 回撤等统计。 */
    public double netProfit() {
        return profit + swap;
    }
}
