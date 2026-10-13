package com.benmao.quanta.review;

/**
 * 成本分析（设计 §12 / §13.4）：点差 / 滑点 / swap 拆解与占比，识别「是否被点差吃掉利润」。
 *
 * <p><b>数据基准</b>：回测明细（{@code backtest_run} + {@code backtest_trade}）。
 * 因为回测成本模型是<b>显式可拆</b>的（{@code spread}/{@code slippage}/{@code swap_on} 均为已知参数）；
 * 而实盘成交价已内含真实点差与滑点、无法从订单中分离，故不以实盘为基准。
 *
 * <p>口径（单边承担一个完整点差，往返 2 次成交；与 {@code backtest.CostModel} 一致，点差含单边系数）：
 * <ul>
 *   <li>点差成本 = Σ {@code spread × spreadSideFactor × point × 合约乘数 × 手数 × 2}；</li>
 *   <li>滑点成本 = Σ {@code slippage × point × 合约乘数 × 手数 × 2}；</li>
 *   <li>swap 成本 = Σ {@code swap} 的绝对值（swap 为负即隔夜费成本）；</li>
 *   <li>毛利润 = Σ {@code profit} + 点差成本 + 滑点成本（还原「未扣点差/滑点」的理想毛利润）；</li>
 *   <li>净利润 = Σ {@code (profit + swap)}。</li>
 * </ul>
 *
 * @param runId         回测任务 id
 * @param strategyId    策略 id
 * @param symbol        品种
 * @param timeframe     重放粒度
 * @param spreadPoints  该回测档位点差（points）
 * @param slippagePoints 该回测档位滑点（points）
 * @param swapOn        是否计入隔夜费
 * @param tradeCount    成交笔数
 * @param netProfit     净利润（USD）
 * @param grossProfit   毛利润（USD，未扣点差/滑点）
 * @param spreadCost    点差成本（USD）
 * @param slippageCost  滑点成本（USD）
 * @param swapCost      swap 成本（USD，正数表示成本）
 * @param totalCost     成本合计（USD）
 * @param spreadPct     点差占成本比（%；成本为 0 时为 null）
 * @param slippagePct   滑点占成本比（%；成本为 0 时为 null）
 * @param swapPct       swap 占成本比（%；成本为 0 时为 null）
 * @param eatenByCost   是否被成本吃掉利润（毛利润 &gt; 0 但净利润 ≤ 0）
 * @param verdict       结论文本
 * @param disclaimer    免责声明（点差/滑点为固定近似值，不得冒充实测）
 */
public record CostAnalysis(Long runId,
                           Long strategyId,
                           String symbol,
                           String timeframe,
                           double spreadPoints,
                           double slippagePoints,
                           boolean swapOn,
                           int tradeCount,
                           double netProfit,
                           double grossProfit,
                           double spreadCost,
                           double slippageCost,
                           double swapCost,
                           double totalCost,
                           Double spreadPct,
                           Double slippagePct,
                           Double swapPct,
                           boolean eatenByCost,
                           String verdict,
                           String disclaimer) {
}
