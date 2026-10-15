package com.benmao.quanta.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 「笨猫马丁-网格双边」策略集中配置（前缀 {@code quanta.strategy.basket}）。
 *
 * <p><b>不写死原则</b>：马丁/网格/锁闸门/形态判定/时段的全部可调量集中于此，核心引擎与 runner
 * 不得出现魔法数字；默认值依据《策略.txt》结果表口径（见方案 §6）。
 */
@Component
@ConfigurationProperties(prefix = "quanta.strategy.basket")
public class BasketStrategyProperties {

    /** 交易时段与形态判定。 */
    private Session session = new Session();

    /** 马丁参数。 */
    private Martin martin = new Martin();

    /** 网格参数。 */
    private Grid grid = new Grid();

    /** 锁闸门（净值级硬约束）。 */
    private Locks locks = new Locks();

    /** 形态判定。 */
    public static class Session {
        /** 时段起点小时（含）。 */
        private int startHour = 8;
        /** 时段终点小时（不含）。 */
        private int endHour = 22;
        /** 时段/日期所依据的时区（库内 K 线 epoch 为 MT5 服务器时间原样透传，按 UTC 解读即平台时间）。 */
        private String timeZone = "UTC";
        /** 形态判定：快慢线相对差 ≤ 该百分比视为「重合」。 */
        private double trendEpsilonPct = 0.05;
        /** EMA 快线周期。 */
        private int emaFast = 50;
        /** EMA 慢线周期。 */
        private int emaSlow = 200;
        /** ADX 周期。 */
        private int adxPeriod = 14;
        /** ADX ≤ 该值且线重合 → GRID（震荡）。 */
        private double adxGridMax = 20.0;
        /** ADX ≥ 该值且线分离 → MARTIN（趋势）。 */
        private double adxTrendMin = 25.0;

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

        public String getTimeZone() {
            return timeZone;
        }

        public void setTimeZone(String timeZone) {
            this.timeZone = timeZone;
        }

        public double getTrendEpsilonPct() {
            return trendEpsilonPct;
        }

        public void setTrendEpsilonPct(double trendEpsilonPct) {
            this.trendEpsilonPct = trendEpsilonPct;
        }

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

        public double getAdxGridMax() {
            return adxGridMax;
        }

        public void setAdxGridMax(double adxGridMax) {
            this.adxGridMax = adxGridMax;
        }

        public double getAdxTrendMin() {
            return adxTrendMin;
        }

        public void setAdxTrendMin(double adxTrendMin) {
            this.adxTrendMin = adxTrendMin;
        }
    }

    /** 马丁参数。 */
    public static class Martin {
        /** 最大腿数（锁死）。 */
        private int maxLevels = 5;
        /**
         * 手数倍增因子：{@code 1.0} = <b>等手数</b>（每腿同手，不马丁）。
         *
         * <p>《策略.txt》原文为 2.0 倍投，但那是 $10,000 账户口径；小账户下最高腿被放大 16 倍，
         * 单轮亏损会被顶穿。本策略按「所有仓当作一个仓」执行，故默认等手数。
         * 恢复倍投只需把本值改回 2.0（无需改代码）。
         */
        private double multiplier = 1.0;
        /** 基准手数百分比（%）：{@code equity × 该值/100}（$10000 → 0.1 手）。 */
        private double baseLotPct = 0.001;
        /**
         * 单腿<b>纯黑障</b>止损距离（project-points；100 = 1.00 USD）。
         *
         * <p>真正的止损是「整轮止损」（执行器按单轮亏损%主动全平）；本值只是程序/桥接挂掉时的
         * 断电保险，必须<b>远大于</b>整轮止损距离，否则会被券商逐个抢跑、把整轮止损架空。
         * 默认 3000 = 30.00 USD。<b>注意</b>：调大「单轮亏损%」时需同步调大本值。
         */
        private double slPoints = 3000;
        /**
         * 整轮止盈距离（project-points；100 = 1.00 USD）。
         *
         * <p>整轮止盈价 = 加权均价 ± tpPoints×point×{@link #basketTargetRr}，与 {@link #slPoints} 解耦。
         * 默认 100：满 5 腿（0.1→1.6）在均价回撤 1.00 USD 处平仓，约合 +$310（见《策略.txt》结果表）。
         */
        private double tpPoints = 100;
        /** 逆势补腿距离（project-points；100 = 1.00 USD）。 */
        private double addLegDistancePoints = 100;
        /** 整轮目标盈亏比（整轮止盈距离 = tpPoints×point×该值）。 */
        private double basketTargetRr = 1.0;
        /** 手数步长（GOLD=0.01）。 */
        private double volumeStep = 0.01;
        /** 最小手数。 */
        private double volumeMin = 0.01;
        /** 最大手数上界（保护第 maxLevels 腿不被 R2 拒单）。 */
        private double maxVolume = 2.0;

        public int getMaxLevels() {
            return maxLevels;
        }

        public void setMaxLevels(int maxLevels) {
            this.maxLevels = maxLevels;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getBaseLotPct() {
            return baseLotPct;
        }

        public void setBaseLotPct(double baseLotPct) {
            this.baseLotPct = baseLotPct;
        }

        public double getSlPoints() {
            return slPoints;
        }

        public void setSlPoints(double slPoints) {
            this.slPoints = slPoints;
        }

        public double getTpPoints() {
            return tpPoints;
        }

        public void setTpPoints(double tpPoints) {
            this.tpPoints = tpPoints;
        }

        public double getAddLegDistancePoints() {
            return addLegDistancePoints;
        }

        public void setAddLegDistancePoints(double addLegDistancePoints) {
            this.addLegDistancePoints = addLegDistancePoints;
        }

        public double getBasketTargetRr() {
            return basketTargetRr;
        }

        public void setBasketTargetRr(double basketTargetRr) {
            this.basketTargetRr = basketTargetRr;
        }

        public double getVolumeStep() {
            return volumeStep;
        }

        public void setVolumeStep(double volumeStep) {
            this.volumeStep = volumeStep;
        }

        public double getVolumeMin() {
            return volumeMin;
        }

        public void setVolumeMin(double volumeMin) {
            this.volumeMin = volumeMin;
        }

        public double getMaxVolume() {
            return maxVolume;
        }

        public void setMaxVolume(double maxVolume) {
            this.maxVolume = maxVolume;
        }
    }

    /** 网格参数。 */
    public static class Grid {
        /** 网格步长（project-points；100 ≈ 1.00 USD）。 */
        private double stepPoints = 100;
        /** 每侧最大腿数（双边共 2×）。 */
        private int legsPerSide = 3;
        /** 每腿手数（所有腿同手，不马丁）。 */
        private double volume = 0.01;
        /** 单腿目标盈亏比（独立 TP，不均价平仓）。 */
        private double targetRr = 1.0;
        /** 单腿止损倍数（sl = 入场 ∓ step×该值）。 */
        private double slMult = 1.0;
        /** 挂单有效期（小时；超时自动撤）。 */
        private int expireHours = 8;
        /** 取区间高低所用的回看 K 线根数。 */
        private int rangeWindow = 24;

        public double getStepPoints() {
            return stepPoints;
        }

        public void setStepPoints(double stepPoints) {
            this.stepPoints = stepPoints;
        }

        public int getLegsPerSide() {
            return legsPerSide;
        }

        public void setLegsPerSide(int legsPerSide) {
            this.legsPerSide = legsPerSide;
        }

        public double getVolume() {
            return volume;
        }

        public void setVolume(double volume) {
            this.volume = volume;
        }

        public double getTargetRr() {
            return targetRr;
        }

        public void setTargetRr(double targetRr) {
            this.targetRr = targetRr;
        }

        public double getSlMult() {
            return slMult;
        }

        public void setSlMult(double slMult) {
            this.slMult = slMult;
        }

        public int getExpireHours() {
            return expireHours;
        }

        public void setExpireHours(int expireHours) {
            this.expireHours = expireHours;
        }

        public int getRangeWindow() {
            return rangeWindow;
        }

        public void setRangeWindow(int rangeWindow) {
            this.rangeWindow = rangeWindow;
        }
    }

    /** 锁闸门（净值级硬约束）。 */
    public static class Locks {
        /** 轮级净值硬止损（%）：浮亏 ≥ cycleStartEquity×该值 → 强平整轮。 */
        private double maxSingleLossPct = 5.0;
        /** 日亏锁定（%）：当日净值跌幅 ≥ 该值 → 当日禁止新开轮并强平。 */
        private double maxDailyLossPct = 3.0;
        /** 总亏锁定（%）：相对起始净值跌幅 ≥ 该值 → 永久禁止新开轮（需人工解除）。 */
        private double maxTotalLossPct = 10.0;
        /** 锁定持续小时（实盘用；回测按日重置日锁）。 */
        private int lockHours = 24;

        public double getMaxSingleLossPct() {
            return maxSingleLossPct;
        }

        public void setMaxSingleLossPct(double maxSingleLossPct) {
            this.maxSingleLossPct = maxSingleLossPct;
        }

        public double getMaxDailyLossPct() {
            return maxDailyLossPct;
        }

        public void setMaxDailyLossPct(double maxDailyLossPct) {
            this.maxDailyLossPct = maxDailyLossPct;
        }

        public double getMaxTotalLossPct() {
            return maxTotalLossPct;
        }

        public void setMaxTotalLossPct(double maxTotalLossPct) {
            this.maxTotalLossPct = maxTotalLossPct;
        }

        public int getLockHours() {
            return lockHours;
        }

        public void setLockHours(int lockHours) {
            this.lockHours = lockHours;
        }
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Martin getMartin() {
        return martin;
    }

    public void setMartin(Martin martin) {
        this.martin = martin;
    }

    public Grid getGrid() {
        return grid;
    }

    public void setGrid(Grid grid) {
        this.grid = grid;
    }

    public Locks getLocks() {
        return locks;
    }

    public void setLocks(Locks locks) {
        this.locks = locks;
    }
}
