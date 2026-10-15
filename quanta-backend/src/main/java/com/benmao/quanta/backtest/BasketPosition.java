package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

import java.util.ArrayList;
import java.util.List;

/**
 * 多腿持仓（一个「轮」）：可变状态，腿的增删发生在本对象内。
 *
 * <p>马丁轮：同向多腿、手数递增，整轮按 {@link #basketTp()}（加权均价 ± 目标距离）平仓；
 * 每腿亦可各自触发自身 {@code sl}。网格：逐腿独立 {@code tp/sl}。
 */
public final class BasketPosition {

    private final int cycleNo;
    private final RiskSide side;
    private final BasketEngineMode mode;
    private final double cycleStartEquity;
    private final double baseVolume;
    private final List<BasketLeg> legs = new ArrayList<>();
    private final int openIndex;
    private double basketTp = Double.NaN;

    public BasketPosition(int cycleNo, RiskSide side, BasketEngineMode mode, double cycleStartEquity,
                          double baseVolume, int openIndex) {
        this.cycleNo = cycleNo;
        this.side = side;
        this.mode = mode;
        this.cycleStartEquity = cycleStartEquity;
        this.baseVolume = baseVolume;
        this.openIndex = openIndex;
    }

    public int cycleNo() {
        return cycleNo;
    }

    public RiskSide side() {
        return side;
    }

    public BasketEngineMode mode() {
        return mode;
    }

    public double cycleStartEquity() {
        return cycleStartEquity;
    }

    public double baseVolume() {
        return baseVolume;
    }

    public int openIndex() {
        return openIndex;
    }

    public List<BasketLeg> legs() {
        return legs;
    }

    public void addLeg(BasketLeg leg) {
        legs.add(leg);
    }

    public void removeLeg(BasketLeg leg) {
        legs.remove(leg);
    }

    public boolean isEmpty() {
        return legs.isEmpty();
    }

    public int level() {
        return legs.size();
    }

    public double basketTp() {
        return basketTp;
    }

    public void setBasketTp(double v) {
        this.basketTp = v;
    }

    /** 按手数加权的均价（空腿返回 NaN）。 */
    public double avgEntry() {
        return MartinMath.avgEntry(legs);
    }

    /** 总手数。 */
    public double totalVolume() {
        double v = 0;
        for (BasketLeg l : legs) {
            v += l.volume();
        }
        return v;
    }

    /** 浮动盈亏（以 {@code refPrice} 为平仓参考价，含成本前的价差口径）。 */
    public double floatingPnl(double refPrice, double contractSize) {
        double pnl = 0;
        for (BasketLeg l : legs) {
            double diff = l.side() == RiskSide.BUY ? (refPrice - l.entryPrice()) : (l.entryPrice() - refPrice);
            pnl += diff * contractSize * l.volume();
        }
        return pnl;
    }
}
