package com.benmao.quanta.review;

/**
 * 策略评价（设计 §12：按策略维度统计，产出「是否该切换策略」建议）。
 *
 * @param strategyId   策略 id
 * @param strategyName 策略名
 * @param status       策略状态（ACTIVE/DRAFT…）
 * @param tradeCount   已平仓交易数（按会话归属策略）
 * @param winRatePct   胜率（%）
 * @param profitFactor 盈亏比
 * @param expectancy   期望（每笔平均盈亏）
 * @param maxDrawdown  最大回撤（USD）
 * @param verdict      结论（样本是否足够 + 是否达标）
 * @param advice       建议（含「是否该切换策略」）
 */
public record StrategyEvaluation(Long strategyId,
                                 String strategyName,
                                 String status,
                                 int tradeCount,
                                 double winRatePct,
                                 double profitFactor,
                                 double expectancy,
                                 double maxDrawdown,
                                 String verdict,
                                 String advice) {
}
