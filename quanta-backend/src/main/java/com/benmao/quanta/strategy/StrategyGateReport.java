package com.benmao.quanta.strategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略回测验收门禁报告（P0清单 M9 / 设计 §14.4）：
 *
 * <p>对某策略在 GOLD 上跑「全样本 / 样本内(A) / 样本外(B)」三段回测，逐条判定门禁：
 * <ol>
 *   <li>最小交易样本量达标；</li>
 *   <li>门禁点差档（默认 30）下期望为正；</li>
 *   <li>样本外(B)仍为正（防过拟合）；</li>
 *   <li>最大回撤 ≤ 上限（账户级，占初始权益百分比）；</li>
 *   <li>胜率 &gt; 1/(1+R)（盈亏比与胜率匹配）。</li>
 * </ol>
 * 只有 {@link #passed()} 为真且经人工批准，才可置策略为 {@code ACTIVE}。
 *
 * @param abConclusion 样本内(A) → 样本外(B) 的对比结论（防过拟合判据）
 * @param verdict      总判据
 */
public record StrategyGateReport(Long strategyId,
                                 String strategyName,
                                 String ruleKey,
                                 String symbol,
                                 String timeframe,
                                 String regimeType,
                                 double targetRr,
                                 LocalDateTime from,
                                 LocalDateTime to,
                                 LocalDateTime splitPoint,
                                 double inSampleRatio,
                                 int spreadPoints,
                                 int slippagePoints,
                                 Segment full,
                                 Segment inSample,
                                 Segment outOfSample,
                                 List<Criterion> criteria,
                                 boolean passed,
                                 String abConclusion,
                                 String verdict,
                                 String disclaimer) {

    /** 单段回测摘要（轮级口径：tradeCount/cycleCount 对多腿策略=轮数）。 */
    public record Segment(Long runId,
                          int tradeCount,
                          double winRatePct,
                          double profitFactor,
                          double maxDrawdown,
                          double maxDrawdownPct,
                          double netProfit,
                          double expectancy,
                          /** 轮数（多腿策略=轮数；单仓策略=成交笔数）。 */
                          int cycleCount,
                          /** 最长连续净亏轮数（BASKET 判据 c6）。 */
                          int maxLosingCycles,
                          /** 平均每轮档数（BASKET 判据 c7）。 */
                          double avgLevelsPerCycle) {

        /** 兼容构造：旧 8 参调用点（轮数=笔数、连亏轮=0、平均档数=1.00）。 */
        public Segment(Long runId, int tradeCount, double winRatePct, double profitFactor, double maxDrawdown,
                       double maxDrawdownPct, double netProfit, double expectancy) {
            this(runId, tradeCount, winRatePct, profitFactor, maxDrawdown, maxDrawdownPct, netProfit, expectancy,
                    tradeCount, 0, 1.0);
        }
    }

    /** 单条门禁判据。 */
    public record Criterion(String id,
                            String name,
                            boolean pass,
                            String actual,
                            String threshold,
                            String message) {
    }
}
