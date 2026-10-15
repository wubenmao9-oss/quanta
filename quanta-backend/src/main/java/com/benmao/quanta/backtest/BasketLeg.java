package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 马丁/网格的一「腿」：同一方向上不同手数、不同入场价的持仓单元。
 *
 * @param level      腿序号（1 起）
 * @param side       方向
 * @param volume     手数
 * @param openIndex  开仓所在 K 线索引
 * @param entryPrice 开仓成交价（已含成本）
 * @param sl         该腿止损价
 * @param tp         该腿止盈价（马丁腿通常为 null，整轮走 {@link BasketPosition#basketTp()}）
 */
public record BasketLeg(int level, RiskSide side, double volume, int openIndex,
                        double entryPrice, double sl, Double tp) {
}
