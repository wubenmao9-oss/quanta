package com.benmao.quanta.backtest;

import java.util.List;

/**
 * 单档回测的引擎输出（未落库前的中间结果）。
 *
 * @param maxDrawdown 最大回撤（USD，基于已实现权益曲线）
 */
public record EngineResult(List<ClosedTrade> trades,
                           double initialEquity,
                           double finalEquity,
                           double maxDrawdown) {
}
