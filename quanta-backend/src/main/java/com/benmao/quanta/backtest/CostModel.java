package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 回测成本模型（设计 §13.3）：点差 + 滑点 + 隔夜费 swap。
 *
 * <p><b>点差（单边整点差）</b>：设计明确「买开按 ask、卖平按 bid，单边承担一个完整点差」。
 * 故每边成交价 = 参考价 ± {@code (spreadPoints × spreadSideFactor + slippagePoints) × point}：
 * <ul>
 *   <li>买（开多 / 平空）→ 参考价 <b>加</b> 成本（吃到更高的 ask）；</li>
 *   <li>卖（开空 / 平多）→ 参考价 <b>减</b> 成本（吃到更低的 bid）。</li>
 * </ul>
 * {@code spreadSideFactor} 默认 1.0（每边承担一个完整点差，保守取高）。当为 0 且无滑点时即"零成本对照"。
 *
 * <p>盈亏按合约规格换算：{@code 盈亏 = 价差 × contractSizePerLot × volume}（GOLD：1 手=100 盎司，
 * 0.01 手时 1 USD 价差 = 1 USD 盈亏）。
 */
public class CostModel {

    private final double point;
    private final double spreadPoints;
    private final double slippagePoints;
    private final double spreadSideFactor;

    public CostModel(double point, double spreadPoints, double slippagePoints, double spreadSideFactor) {
        this.point = point;
        this.spreadPoints = spreadPoints;
        this.slippagePoints = slippagePoints;
        this.spreadSideFactor = spreadSideFactor;
    }

    /** 每边承担的成本（价格单位）。 */
    public double costPerSide() {
        return (spreadPoints * spreadSideFactor + slippagePoints) * point;
    }

    /** 市价成交价：买单加成本、卖单减成本。 */
    public double fillPrice(RiskSide side, double reference) {
        double cost = costPerSide();
        return side == RiskSide.BUY ? reference + cost : reference - cost;
    }

    /**
     * 已实现盈亏（不含 swap）。
     *
     * @param side       开仓方向
     * @param openPrice  开仓成交价（已含成本）
     * @param closePrice 平仓成交价（已含成本）
     */
    public double profit(RiskSide side, double openPrice, double closePrice, double volume, double contractSize) {
        double diff = side == RiskSide.BUY ? (closePrice - openPrice) : (openPrice - closePrice);
        return diff * contractSize * volume;
    }

    public double getSpreadPoints() {
        return spreadPoints;
    }

    public double getSlippagePoints() {
        return slippagePoints;
    }
}
