package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

import java.util.List;

/**
 * 多腿回测的引擎输出（未落库前的中间结果；与 {@link EngineResult} 并行存在）。
 *
 * @param maxDrawdown 最大回撤（USD，基于已实现权益曲线）
 */
public record BasketEngineResult(List<ClosedTrade> trades,
                                 List<CycleSummary> cycles,
                                 double initialEquity,
                                 double finalEquity,
                                 double maxDrawdown) {

    /**
     * 一轮（basket lifecycle）的汇总（轮级口径统计用）。
     *
     * @param levels        本轮总腿数
     * @param avgEntry      本轮加权均价
     * @param netProfit     本轮净盈亏（含 swap）
     * @param holdSec       本轮持仓秒数（首腿开仓 → 最后腿平仓）
     * @param closeReason   本轮平仓原因（最后一次平仓事件的 reason）
     */
    public record CycleSummary(int cycleNo,
                               BasketEngineMode mode,
                               RiskSide side,
                               int levels,
                               double totalVolume,
                               double avgEntry,
                               double netProfit,
                               int holdSec,
                               String closeReason) {
    }
}
