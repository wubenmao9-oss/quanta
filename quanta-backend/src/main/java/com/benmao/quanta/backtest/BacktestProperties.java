package com.benmao.quanta.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回测层集中配置（设计 §13 / §18）：成本建模、点差/滑点档位、合约规格、默认策略参数。
 *
 * <p><b>不写死原则</b>：回测所有可调量（点差/滑点档位、swap、合约规格、策略参数、免责声明）都在此处集中管理，
 * 核心引擎代码不得出现魔法数字。
 */
@Component
@ConfigurationProperties(prefix = "quanta.backtest")
public class BacktestProperties {

    /** 回测品种（券商真实名，一期 GOLD）。 */
    private String symbol = "GOLD";

    /** 重放粒度（设计 §13.1：因需盘中判 SL/TP，建议 M1）。 */
    private String timeframe = "M1";

    /** 初始权益（USD），用于回撤/曲线基准。 */
    private double initialEquity = 500.0;

    /** 合约规格（GOLD：1 手 = 100 盎司 → 1 USD 价差 = 100 USD/手）。 */
    private double contractSizePerLot = 100.0;

    /** 最小价格变动（GOLD = 0.01）。 */
    private double point = 0.01;

    /** 回测固定手数。 */
    private double volume = 0.01;

    /** 点差档位（points），设计默认 {0,16,30,50}；16=当前实测、30=常规上限、50=极端。 */
    private List<Integer> spreadTiers = List.of(0, 16, 30, 50);

    /** 滑点档位（points），设计默认 {0,2,5}。 */
    private List<Integer> slippageTiers = List.of(0, 2, 5);

    /** 单档回测未显式给滑点时使用的默认值（points）。 */
    private int defaultSlippage = 0;

    /**
     * 单边点差系数：设计 §13.3「买开按 ask、卖平按 bid，单边承担一个完整点差」。
     * <p>取值 1.0 = 每边承担 1 个完整点差（保守，往高取）；如需更贴近真实（mid±半价差）可调 0.5。
     */
    private double spreadSideFactor = 1.0;

    /** 是否计入隔夜费 swap。 */
    private boolean swapOn = true;

    /** 每手每日隔夜费（USD，负值 = 成本）。 */
    private double swapPerLotPerDay = -0.5;

    /** 计三倍 swap 的星期（券商惯例周三）。 */
    private String swapTripleWeekday = "WEDNESDAY";

    /** 服务器每日结算时间点（小时，0~23），持仓跨越该点计一次 swap。 */
    private int settlementHour = 0;

    /** 拉取历史 K 线的默认天数（回填用）。 */
    private int backfillDays = 30;

    /** 回填时按天分块向历史请求的窗口大小（天；小窗口便于终端按需补下历史）。 */
    private int fetchChunkDays = 3;

    /** 点差合法上界（points，防幻觉：超界拒绝并回执范围）。 */
    private int maxSpreadPoints = 200;

    /** 滑点合法上界（points）。 */
    private int maxSlippagePoints = 100;

    /** 允许的重放周期（防幻觉白名单）。 */
    private List<String> allowedTimeframes = List.of("M1", "M5", "M15", "M30", "H1", "H4", "D1");

    /** 报告免责声明（显式标注点差/swap 为固定近似，不得冒充实测）。 */
    private String disclaimer = "点差/滑点/swap 为固定近似值（MT5 仅提供 OHLC，无历史逐笔买卖价），"
            + "回测结果不等同实盘实测，不得作为收益承诺。";

    /** 默认策略参数（RULE 模式示例：EMA 金叉死叉 + ATR 止损）。 */
    private Strategy strategy = new Strategy();

    /** EMA + ATR 策略参数。 */
    public static class Strategy {
        /** 快线 EMA 周期。 */
        private int emaFast = 9;
        /** 慢线 EMA 周期。 */
        private int emaSlow = 21;
        /** ATR 周期。 */
        private int atrPeriod = 14;
        /** 止损距离 = ATR × 该倍数。 */
        private double atrStopMult = 1.5;
        /** 目标盈亏比（TP = 入场 ± targetRr × 止损距离）。 */
        private double targetRr = 2.0;

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

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public double getInitialEquity() {
        return initialEquity;
    }

    public void setInitialEquity(double initialEquity) {
        this.initialEquity = initialEquity;
    }

    public double getContractSizePerLot() {
        return contractSizePerLot;
    }

    public void setContractSizePerLot(double contractSizePerLot) {
        this.contractSizePerLot = contractSizePerLot;
    }

    public double getPoint() {
        return point;
    }

    public void setPoint(double point) {
        this.point = point;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public List<Integer> getSpreadTiers() {
        return spreadTiers;
    }

    public void setSpreadTiers(List<Integer> spreadTiers) {
        this.spreadTiers = spreadTiers;
    }

    public List<Integer> getSlippageTiers() {
        return slippageTiers;
    }

    public void setSlippageTiers(List<Integer> slippageTiers) {
        this.slippageTiers = slippageTiers;
    }

    public int getDefaultSlippage() {
        return defaultSlippage;
    }

    public void setDefaultSlippage(int defaultSlippage) {
        this.defaultSlippage = defaultSlippage;
    }

    public double getSpreadSideFactor() {
        return spreadSideFactor;
    }

    public void setSpreadSideFactor(double spreadSideFactor) {
        this.spreadSideFactor = spreadSideFactor;
    }

    public boolean isSwapOn() {
        return swapOn;
    }

    public void setSwapOn(boolean swapOn) {
        this.swapOn = swapOn;
    }

    public double getSwapPerLotPerDay() {
        return swapPerLotPerDay;
    }

    public void setSwapPerLotPerDay(double swapPerLotPerDay) {
        this.swapPerLotPerDay = swapPerLotPerDay;
    }

    public String getSwapTripleWeekday() {
        return swapTripleWeekday;
    }

    public void setSwapTripleWeekday(String swapTripleWeekday) {
        this.swapTripleWeekday = swapTripleWeekday;
    }

    public int getSettlementHour() {
        return settlementHour;
    }

    public void setSettlementHour(int settlementHour) {
        this.settlementHour = settlementHour;
    }

    public int getBackfillDays() {
        return backfillDays;
    }

    public void setBackfillDays(int backfillDays) {
        this.backfillDays = backfillDays;
    }

    public int getFetchChunkDays() {
        return fetchChunkDays;
    }

    public void setFetchChunkDays(int fetchChunkDays) {
        this.fetchChunkDays = fetchChunkDays;
    }

    public int getMaxSpreadPoints() {
        return maxSpreadPoints;
    }

    public void setMaxSpreadPoints(int maxSpreadPoints) {
        this.maxSpreadPoints = maxSpreadPoints;
    }

    public int getMaxSlippagePoints() {
        return maxSlippagePoints;
    }

    public void setMaxSlippagePoints(int maxSlippagePoints) {
        this.maxSlippagePoints = maxSlippagePoints;
    }

    public List<String> getAllowedTimeframes() {
        return allowedTimeframes;
    }

    public void setAllowedTimeframes(List<String> allowedTimeframes) {
        this.allowedTimeframes = allowedTimeframes;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }
}
