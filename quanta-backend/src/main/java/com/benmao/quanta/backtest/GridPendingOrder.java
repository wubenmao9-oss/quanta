package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 回测内模拟的<b>挂单</b>（网格双边使用）：止盈损价随挂单类型与步长配置生成，成交价含跳空保护。
 *
 * <p>触发判定以每根 K 线的 high/low 为准；成交参考价用本根开盘价做跳空保护
 * （BUY_STOP：{@code max(price, open)}；SELL_STOP：{@code min(price, open)}），再经 {@link CostModel} 加成本。
 */
public final class GridPendingOrder {

    /** 挂单类型（回测内支持四种；一期网格用 STOP 双边）。 */
    public enum Kind {
        BUY_STOP, SELL_STOP, BUY_LIMIT, SELL_LIMIT
    }

    private final long id;
    private final Kind kind;
    private final double price;
    private final double volume;
    private final int placedIndex;
    private final long expireAtEpoch;
    private final double stepPoints;
    private final double targetRr;
    private final double slMult;

    public GridPendingOrder(long id, Kind kind, double price, double volume, int placedIndex,
                            long expireAtEpoch, double stepPoints, double targetRr, double slMult) {
        this.id = id;
        this.kind = kind;
        this.price = price;
        this.volume = volume;
        this.placedIndex = placedIndex;
        this.expireAtEpoch = expireAtEpoch;
        this.stepPoints = stepPoints;
        this.targetRr = targetRr;
        this.slMult = slMult;
    }

    public long id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public double price() {
        return price;
    }

    public double volume() {
        return volume;
    }

    public int placedIndex() {
        return placedIndex;
    }

    public long expireAtEpoch() {
        return expireAtEpoch;
    }

    public double stepPoints() {
        return stepPoints;
    }

    public double targetRr() {
        return targetRr;
    }

    public double slMult() {
        return slMult;
    }

    /** 该挂单成交方向。 */
    public RiskSide side() {
        return (kind == Kind.BUY_STOP || kind == Kind.BUY_LIMIT) ? RiskSide.BUY : RiskSide.SELL;
    }

    /** 本根 K 线是否触发。 */
    public boolean triggeredBy(double high, double low) {
        return switch (kind) {
            case BUY_STOP, SELL_LIMIT -> high >= price;
            case SELL_STOP, BUY_LIMIT -> low <= price;
        };
    }

    /** 成交参考价（跳空保护）。 */
    public double fillRef(double barOpen) {
        return switch (kind) {
            case BUY_STOP, BUY_LIMIT -> Math.max(price, barOpen);
            case SELL_STOP, SELL_LIMIT -> Math.min(price, barOpen);
        };
    }
}
