package com.benmao.quanta.backtest;

import java.util.List;

/**
 * 交给 {@link BasketRuleRunner} 的<b>只读</b>决策视图（防未来函数：行情只能经 {@link ReplayView} 读取）。
 *
 * @param view           行情视图（游标受限）
 * @param basket         当前轮持仓（无轮为 null）
 * @param pendings       当前挂单（只读副本）
 * @param cycleNo        本轮编号（下一个新建轮将使用 cycleNo+1）
 * @param equity         当前已实现权益
 * @param dayStartEquity 当日起始权益（跨日重置）
 * @param point          最小价格变动（GOLD=0.01）
 * @param contractSize   合约规格（GOLD：1 手=100 盎司）
 * @param dayLocked      日亏锁定（当日内禁止新开轮）
 * @param totalLocked    总亏锁定（禁止新开轮，需人工解除）
 * @param newsBlackout   新闻黑窗（禁止新开轮）
 */
public record BasketState(ReplayView view,
                          BasketPosition basket,
                          List<GridPendingOrder> pendings,
                          int cycleNo,
                          double equity,
                          double dayStartEquity,
                          double cycleStartEquity,
                          double point,
                          double contractSize,
                          boolean dayLocked,
                          boolean totalLocked,
                          boolean newsBlackout) {
}
