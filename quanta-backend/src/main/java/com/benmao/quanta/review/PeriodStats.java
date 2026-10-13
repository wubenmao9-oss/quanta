package com.benmao.quanta.review;

import java.time.LocalDateTime;

/**
 * 周期统计（设计 §12：胜率、盈亏比、最大回撤、连亏次数）。
 *
 * <p>口径：仅统计 {@code trade_order.status = 'CLOSED'} 的已平仓单，盈亏 = {@code profit + swap}（USD）。
 *
 * @param symbol           品种（可空 = 全品种）
 * @param since            统计起点（可空）
 * @param until            统计终点（可空）
 * @param tradeCount       已平仓交易数
 * @param winCount         盈利单数（pnl &gt; 0）
 * @param lossCount        亏损单数（pnl &lt; 0）
 * @param winRatePct       胜率（%）
 * @param grossProfit      盈利单净额之和（正）
 * @param grossLoss        亏损单净额绝对值之和（正）；盈亏比 = grossProfit / grossLoss
 * @param profitFactor     盈亏比（无亏损单时为 {@link Double#POSITIVE_INFINITY}）
 * @param netProfit        净盈亏（全部单合计）
 * @param expectancy       期望（每笔平均盈亏）
 * @param maxDrawdown      最大回撤（USD，基于初始权益 + 逐笔累计盈亏的权益曲线）
 * @param maxConsecutiveLoss 最大连亏次数
 * @param avgHoldSec       平均持仓时长（秒）
 */
public record PeriodStats(String symbol,
                          LocalDateTime since,
                          LocalDateTime until,
                          int tradeCount,
                          int winCount,
                          int lossCount,
                          double winRatePct,
                          double grossProfit,
                          double grossLoss,
                          double profitFactor,
                          double netProfit,
                          double expectancy,
                          double maxDrawdown,
                          int maxConsecutiveLoss,
                          int avgHoldSec) {
}
