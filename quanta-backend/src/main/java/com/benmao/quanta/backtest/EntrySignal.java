package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 规则策略给出的<b>开仓意图</b>（RULE 模式下不涉及任何 LLM）。
 *
 * @param side         方向
 * @param stopDistance 止损距离（绝对价格距离，如 ATR × 倍数）
 * @param targetRr     目标盈亏比（TP = 入场 ± targetRr × stopDistance）
 */
public record EntrySignal(RiskSide side, double stopDistance, double targetRr) {
}
