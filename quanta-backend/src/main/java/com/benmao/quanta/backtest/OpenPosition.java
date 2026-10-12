package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 回测持仓快照（供规则策略在决策时感知"当前是否已持仓"，避免重复开仓）。
 *
 * @param side       方向
 * @param entryIndex 开仓所在 K 线索引
 * @param entryPrice 开仓成交价（已含成本）
 * @param sl         止损价
 * @param tp         止盈价
 */
public record OpenPosition(RiskSide side, int entryIndex, double entryPrice, double sl, double tp) {
}
