package com.benmao.quanta.backtest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单档回测报告（接口返回 + 落库摘要）。
 *
 * @param grossLoss 亏损单净额之和（<b>负值</b>）；profitFactor = grossProfit / |grossLoss|
 */
public record BacktestReport(Long runId,
                             String strategyKey,
                             String strategyName,
                             String symbol,
                             String timeframe,
                             LocalDateTime startTime,
                             LocalDateTime endTime,
                             int barCount,
                             String spreadMode,
                             double spreadPoints,
                             double slippagePoints,
                             boolean swapOn,
                             int tradeCount,
                             double winRatePct,
                             double profitFactor,
                             double maxDrawdown,
                             int avgHoldSec,
                             double expectancy,
                             double grossProfit,
                             double grossLoss,
                             double netProfit,
                             double initialEquity,
                             double finalEquity,
                             String disclaimer,
                             List<ClosedTrade> trades) {
}
