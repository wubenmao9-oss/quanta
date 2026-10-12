package com.benmao.quanta.backtest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 点差敏感性扫描结果（设计 §13.4）：同一策略、同一区间跑多个点差档位，汇总成
 * 「点差 — 胜率 / 盈亏比 / 交易次数」表 + 稳健性判据。
 *
 * @param verdict 稳健性结论（依据"高成本档（如 30 点差）下期望是否仍为正"）
 */
public record TierScanResult(String symbol,
                             String timeframe,
                             String strategyKey,
                             LocalDateTime startTime,
                             LocalDateTime endTime,
                             int barCount,
                             List<TierSummary> tiers,
                             String verdict,
                             String disclaimer) {

    /** 单档摘要。 */
    public record TierSummary(Long runId,
                              double spreadPoints,
                              double slippagePoints,
                              int tradeCount,
                              double winRatePct,
                              double profitFactor,
                              double maxDrawdown,
                              double netProfit,
                              double expectancy) {
    }
}
