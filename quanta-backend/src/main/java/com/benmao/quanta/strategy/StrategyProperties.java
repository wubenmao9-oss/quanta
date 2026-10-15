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

    /** 演练高频策略参数（SCALP）：EMA 金叉死叉 + ATR 止损，无趋势过滤。 */
    private Scalp scalp = new Scalp();

    /** 演练高频薅点策略参数（TINY_SCALP）：EMA 判方向 + 固定美元距离止损，无趋势过滤。 */
    private Tiny tiny = new Tiny();

    /** 笨猫黄金策略参数（BENMAO_GOLD）：EMA52 趋势基准 + RSI 动量 + ADX 过滤 + ATR 止损/止盈。 */
    private BenmaoGold benmaoGold = new BenmaoGold();

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
        /** 门禁回测初始权益（基准账户）：决定回撤百分比口径与马丁手数基数。 */
        private double initialEquity = 10000.0;
        /** 多腿/网格（BASKET）门禁：最小轮数（不足则门禁不过）。 */
        private int minCycles = 100;
        /** 多腿/网格（BASKET）门禁：最大连续净亏轮数上限。 */
        private int maxLosingCycles = 3;
        /** 多腿/网格（BASKET）门禁：平均每轮档数上限（应 ≤ 策略 MaxLevels）。 */
        private double maxAvgLevels = 5.0;

        /**
         * 演练模式开关（默认关）。
         *
         * <p>开启后：① 允许对未通过门禁的策略调用 {@code /api/strategy/{id}/drill-approve} 置为 {@code DRILL}
         * （仅用于验证「AI 是否真的会下单」，禁止用于实盘资金）；② 研判时优先选用 {@code DRILL} 策略。
         * 关闭或对策略调用 {@code /drill-revoke} 即可一键回滚。
         */
        private boolean drillEnabled = false;

        public boolean isDrillEnabled() {
            return drillEnabled;
        }

        public void setDrillEnabled(boolean drillEnabled) {
            this.drillEnabled = drillEnabled;
        }

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

        public double getInitialEquity() {
            return initialEquity;
        }

        public void setInitialEquity(double initialEquity) {
            this.initialEquity = initialEquity;
        }

        public int getMinCycles() {
            return minCycles;
        }

        public void setMinCycles(int minCycles) {
            this.minCycles = minCycles;
        }

        public int getMaxLosingCycles() {
            return maxLosingCycles;
        }

        public void setMaxLosingCycles(int maxLosingCycles) {
            this.maxLosingCycles = maxLosingCycles;
        }

        public double getMaxAvgLevels() {
            return maxAvgLevels;
        }

        public void setMaxAvgLevels(double maxAvgLevels) {
            this.maxAvgLevels = maxAvgLevels;
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

    /**
     * 演练高频策略参数（SCALP）：<b>EMA 金叉死叉 + ATR 止损，无 ADX 趋势过滤</b>。
     *
     * <p>定位：仅用于「演练模式」验证决策链路是否能真实下单（高频、允许试错），<b>不追求正期望</b>，
     * 因而通常过不了门禁，只能经 {@code /drill-approve} 置为 {@code DRILL}。
     */
    public static class Scalp {
        /** 快线 EMA 周期。 */
        private int emaFast = 9;
        /** 慢线 EMA 周期。 */
        private int emaSlow = 21;
        /** ATR 周期。 */
        private int atrPeriod = 14;
        /** 止损距离 = ATR × 该倍数。 */
        private double atrStopMult = 1.5;
        /** 目标盈亏比（TP = 入场 ± targetRr × 止损距离）。 */
        private double targetRr = 1.2;

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

    /**
     * 演练高频薅点策略参数（TINY_SCALP）：<b>EMA 判方向 + 固定美元距离止损</b>，无任何趋势过滤。
     *
     * <p>定位：仅用于「演练模式」验证 AI 能否真实下单并<b>高频开仓（一小时不少于 5 单）</b>，
     * 同时每单固定薅小额 —— GOLD 上止损 {@code slDistanceUsd}=2.5 USD、止盈 = 2.5 × targetRr = 3.0 USD。
     * <b>不追求正期望</b>，因而过不了门禁，只能经 {@code /api/strategy/{id}/drill-approve} 置为 {@code DRILL}。
     */
    public static class Tiny {
        /** 快线 EMA 周期。 */
        private int emaFast = 9;
        /** 慢线 EMA 周期。 */
        private int emaSlow = 21;
        /** 固定止损距离（USD；GOLD 上 1 USD = 100 points）。 */
        private double slDistanceUsd = 2.5;
        /** 目标盈亏比（止盈距离 = 止损距离 × targetRr，默认 1.2 → 3.0 USD）。 */
        private double targetRr = 1.2;
        /** 要求大模型给出的最低置信度（演练要求：必须通过置信度门控）。 */
        private double confidenceFloor = 0.7;

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

        public double getSlDistanceUsd() {
            return slDistanceUsd;
        }

        public void setSlDistanceUsd(double slDistanceUsd) {
            this.slDistanceUsd = slDistanceUsd;
        }

        public double getTargetRr() {
            return targetRr;
        }

        public void setTargetRr(double targetRr) {
            this.targetRr = targetRr;
        }

        public double getConfidenceFloor() {
            return confidenceFloor;
        }

        public void setConfidenceFloor(double confidenceFloor) {
            this.confidenceFloor = confidenceFloor;
        }
    }

    /**
     * 笨猫黄金策略参数（BENMAO_GOLD）：<b>单仓趋势跟随</b>，改写自 MT5 源码
     * 「谷歌黄金，修改了动态止盈损，动态仓位，.mq5」。
     *
     * <p>进场（三者同向、一次仅一笔）：价格站上 EMA({@code emaPeriod}=52) 且 RSI({@code rsiPeriod})
     * 高于 {@code rsiMidline} → 做多；价格跌破 EMA52 且 RSI 低于中线 → 做空；同时要求
     * ADX({@code adxPeriod}) &gt; {@code adxThreshold} 且处于 {@code [startHour, endHour)} 时段内。
     *
     * <p>出场：止损 = ATR × {@code atrStopMult}(2.0)，止盈 = 止损 × {@code targetRr}(2.0)
     * （等价源码的 SL=2×ATR / TP=4×ATR）。
     *
     * <p><b>源码有、本项目未覆盖</b>：源码的 1.5×ATR 保本移动止损与按「余额 × 风险%」计算的动态手数，
     * 因 {@code EntrySignal} 只表达「固定止损距离 + 目标盈亏比」而无法落地；手数由回测统一控制。
     */
    public static class BenmaoGold {
        /** 趋势基准 EMA 周期（源码 iMA 固定 52）。 */
        private int emaPeriod = 52;
        /** RSI 周期。 */
        private int rsiPeriod = 14;
        /** RSI 动量中线：多头要求 RSI 高于该值，空头要求低于该值。 */
        private double rsiMidline = 50.0;
        /** ADX 周期。 */
        private int adxPeriod = 14;
        /** ADX 过滤阈值：低于该值不交易（源码 InpADXThreshold=20）。 */
        private double adxThreshold = 20.0;
        /** ATR 周期。 */
        private int atrPeriod = 14;
        /** 止损距离 = ATR × 该倍数（源码 InpStopATR=2.0）。 */
        private double atrStopMult = 2.0;
        /** 目标盈亏比（源码 TP 4×ATR ÷ SL 2×ATR = 2.0）。 */
        private double targetRr = 2.0;
        /** 是否启用交易时段过滤（源码 InpUseTimeFilter=true）。 */
        private boolean useTimeFilter = true;
        /** 时段起点小时（含）。 */
        private int startHour = 8;
        /** 时段终点小时（不含）。 */
        private int endHour = 20;
        /**
         * 时段过滤所依据的时区。
         *
         * <p>库内 K 线 epoch 是 MT5 <b>服务器时间</b>原样透传（落库与回读都用同一时钟时区往返，值不变），
         * 因而按 {@code UTC} 解读得到的小时即等价源码 {@code TimeCurrent(dt)} 的「平台时间」。
         */
        private String timeFilterZone = "UTC";

        public int getEmaPeriod() {
            return emaPeriod;
        }

        public void setEmaPeriod(int emaPeriod) {
            this.emaPeriod = emaPeriod;
        }

        public int getRsiPeriod() {
            return rsiPeriod;
        }

        public void setRsiPeriod(int rsiPeriod) {
            this.rsiPeriod = rsiPeriod;
        }

        public double getRsiMidline() {
            return rsiMidline;
        }

        public void setRsiMidline(double rsiMidline) {
            this.rsiMidline = rsiMidline;
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

        public boolean isUseTimeFilter() {
            return useTimeFilter;
        }

        public void setUseTimeFilter(boolean useTimeFilter) {
            this.useTimeFilter = useTimeFilter;
        }

        public int getStartHour() {
            return startHour;
        }

        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }

        public int getEndHour() {
            return endHour;
        }

        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }

        public String getTimeFilterZone() {
            return timeFilterZone;
        }

        public void setTimeFilterZone(String timeFilterZone) {
            this.timeFilterZone = timeFilterZone;
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

    public Scalp getScalp() {
        return scalp;
    }

    public void setScalp(Scalp scalp) {
        this.scalp = scalp;
    }

    public Tiny getTiny() {
        return tiny;
    }

    public void setTiny(Tiny tiny) {
        this.tiny = tiny;
    }

    public BenmaoGold getBenmaoGold() {
        return benmaoGold;
    }

    public void setBenmaoGold(BenmaoGold benmaoGold) {
        this.benmaoGold = benmaoGold;
    }
}
