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
                             List<ClosedTrade> trades,
                             /** 轮级口径：轮数（多腿策略=轮数；单仓策略=成交笔数）。 */
                             int cycleCount,
                             /** 最长连续净亏轮数（门禁 c6 用）。 */
                             int maxLosingCycles,
                             /** 平均每轮档数（多腿策略用；单仓策略=1.00）。 */
                             double avgLevels) {

    /** 兼容构造器：旧 25 参调用点（无轮级字段）→ 轮数=笔数、连亏轮=0、平均档数=1.00。 */
    public BacktestReport(Long runId, String strategyKey, String strategyName, String symbol, String timeframe,
                          LocalDateTime startTime, LocalDateTime endTime, int barCount, String spreadMode,
                          double spreadPoints, double slippagePoints, boolean swapOn, int tradeCount,
                          double winRatePct, double profitFactor, double maxDrawdown, int avgHoldSec,
                          double expectancy, double grossProfit, double grossLoss, double netProfit,
                          double initialEquity, double finalEquity, String disclaimer, List<ClosedTrade> trades) {
        this(runId, strategyKey, strategyName, symbol, timeframe, startTime, endTime, barCount, spreadMode,
                spreadPoints, slippagePoints, swapOn, tradeCount, winRatePct, profitFactor, maxDrawdown, avgHoldSec,
                expectancy, grossProfit, grossLoss, netProfit, initialEquity, finalEquity, disclaimer, trades,
                tradeCount, 0, 1.0);
    }
}
