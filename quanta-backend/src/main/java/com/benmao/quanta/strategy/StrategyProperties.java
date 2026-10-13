package com.benmao.quanta.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 策略层集中配置（设计 §14 / §18 / P0清单 M9）：<b>门禁阈值</b>与<b>首批两策略参数</b>。
 *
 * <p><b>不写死原则</b>：验收门禁的样本量/门禁点差档/回撤上限/样本内外比例，以及趋势、震荡两策略的指标参数
 * 与目标盈亏比，全部在此集中管理；策略规则代码不得出现魔法数字。
 */
@Component
@ConfigurationProperties(prefix = "quanta.strategy")
public class StrategyProperties {

    /** 回测验收门禁参数（设计 §14.4 防过拟合 + P0清单 M9 门禁）。 */
    private Gate gate = new Gate();

    /** 趋势策略参数（TREND）。 */
    private Trend trend = new Trend();

    /** 震荡策略参数（RANGE）。 */
    private Range range = new Range();

    /** 回测验收门禁参数。 */
    public static class Gate {
        /** 最小交易样本量（不足则判门禁不过）。 */
        private int minTradeCount = 100;
        /** 门禁点差档（points）：该档下期望须为正（设计 §13.4「高成本档期望为正才算真 α」）。 */
        private int spreadPoints = 30;
        /** 门禁滑点档（points）。 */
        private int slippagePoints = 0;
        /** 最大回撤上限（占初始权益的百分比）。 */
        private double maxDrawdownPct = 15.0;
        /** 样本内占比（0~1）：按时间切线，前 inSampleRatio 为样本内(A)，其余为样本外(B)。 */
        private double inSampleRatio = 0.7;

        public int getMinTradeCount() {
            return minTradeCount;
        }

        public void setMinTradeCount(int minTradeCount) {
            this.minTradeCount = minTradeCount;
        }

        public int getSpreadPoints() {
            return spreadPoints;
        }

        public void setSpreadPoints(int spreadPoints) {
            this.spreadPoints = spreadPoints;
        }

        public int getSlippagePoints() {
            return slippagePoints;
        }

        public void setSlippagePoints(int slippagePoints) {
            this.slippagePoints = slippagePoints;
        }

        public double getMaxDrawdownPct() {
            return maxDrawdownPct;
        }

        public void setMaxDrawdownPct(double maxDrawdownPct) {
            this.maxDrawdownPct = maxDrawdownPct;
        }

        public double getInSampleRatio() {
            return inSampleRatio;
        }

        public void setInSampleRatio(double inSampleRatio) {
            this.inSampleRatio = inSampleRatio;
        }
    }

    /** 趋势策略参数：EMA 金叉死叉 + ADX 趋势强度过滤 + ATR 止损。 */
    public static class Trend {
        /** 快线 EMA 周期。 */
        private int emaFast = 12;
        /** 慢线 EMA 周期。 */
        private int emaSlow = 26;
        /** ADX 周期。 */
        private int adxPeriod = 14;
        /** ADX 过滤阈值：仅当 ADX ≥ 阈值（有趋势）才允许信号，抑制震荡期无效交易。 */
        private double adxThreshold = 22.0;
        /** ATR 周期。 */
        private int atrPeriod = 14;
        /** 止损距离 = ATR × 该倍数。 */
        private double atrStopMult = 2.0;
        /** 目标盈亏比（TP = 入场 ± targetRr × 止损距离）。 */
        private double targetRr = 2.5;

        public int getEmaFast() {
            return emaFast;
        }

        public void setEmaFast(int emaFast) {
            this.emaFast = emaFast;
        }

        public int getEmaSlow() {
            return emaSlow;
        }

        public void setEmaSlow(int emaSlow) {
            this.emaSlow = emaSlow;
        }

        public int getAdxPeriod() {
            return adxPeriod;
        }

        public void setAdxPeriod(int adxPeriod) {
            this.adxPeriod = adxPeriod;
        }

        public double getAdxThreshold() {
            return adxThreshold;
        }

        public void setAdxThreshold(double adxThreshold) {
            this.adxThreshold = adxThreshold;
        }

        public int getAtrPeriod() {
            return atrPeriod;
        }

        public void setAtrPeriod(int atrPeriod) {
            this.atrPeriod = atrPeriod;
        }

        public double getAtrStopMult() {
            return atrStopMult;
        }

        public void setAtrStopMult(double atrStopMult) {
            this.atrStopMult = atrStopMult;
        }

        public double getTargetRr() {
            return targetRr;
        }

        public void setTargetRr(double targetRr) {
            this.targetRr = targetRr;
        }
    }

    /** 震荡策略参数：RSI 超买超卖 + 布林带轨道回归 + ATR 止损。 */
    public static class Range {
        /** RSI 周期。 */
        private int rsiPeriod = 14;
        /** RSI 超卖阈值（做多触发）。 */
        private double rsiOversold = 30.0;
        /** RSI 超买阈值（做空触发）。 */
        private double rsiOverbought = 70.0;
        /** 布林带周期。 */
        private int bollPeriod = 20;
        /** 布林带标准差倍数。 */
        private double bollK = 2.0;
        /** ATR 周期。 */
        private int atrPeriod = 14;
        /** 止损距离 = ATR × 该倍数。 */
        private double atrStopMult = 1.5;
        /** 目标盈亏比（震荡回归，高胜率低盈亏比结构）。 */
        private double targetRr = 1.2;

        public int getRsiPeriod() {
            return rsiPeriod;
        }

        public void setRsiPeriod(int rsiPeriod) {
            this.rsiPeriod = rsiPeriod;
        }

        public double getRsiOversold() {
            return rsiOversold;
        }

        public void setRsiOversold(double rsiOversold) {
            this.rsiOversold = rsiOversold;
        }

        public double getRsiOverbought() {
            return rsiOverbought;
        }

        public void setRsiOverbought(double rsiOverbought) {
            this.rsiOverbought = rsiOverbought;
        }

        public int getBollPeriod() {
            return bollPeriod;
        }

        public void setBollPeriod(int bollPeriod) {
            this.bollPeriod = bollPeriod;
        }

        public double getBollK() {
            return bollK;
        }

        public void setBollK(double bollK) {
            this.bollK = bollK;
        }

        public int getAtrPeriod() {
            return atrPeriod;
        }

        public void setAtrPeriod(int atrPeriod) {
            this.atrPeriod = atrPeriod;
        }

        public double getAtrStopMult() {
            return atrStopMult;
        }

        public void setAtrStopMult(double atrStopMult) {
            this.atrStopMult = atrStopMult;
        }

        public double getTargetRr() {
            return targetRr;
        }

        public void setTargetRr(double targetRr) {
            this.targetRr = targetRr;
        }
    }

    public Gate getGate() {
        return gate;
    }

    public void setGate(Gate gate) {
        this.gate = gate;
    }

    public Trend getTrend() {
        return trend;
    }

    public void setTrend(Trend trend) {
        this.trend = trend;
    }

    public Range getRange() {
        return range;
    }

    public void setRange(Range range) {
        this.range = range;
    }
}
