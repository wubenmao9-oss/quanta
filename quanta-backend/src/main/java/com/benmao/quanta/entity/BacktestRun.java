package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** backtest_run 实体（对应表 backtest_run）。 */
public class BacktestRun {

    private Long id;
    private Long strategyId;
    private String symbol;
    private String dataTimeframe;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String params;
    private String spreadMode;
    private BigDecimal spread;
    private BigDecimal slippage;
    private Boolean swapOn;
    private String mode;
    private BigDecimal winRate;
    private BigDecimal profitFactor;
    private BigDecimal maxDrawdown;
    private Integer tradeCount;
    private Integer avgHoldSec;
    /** 轮级口径：轮数（多腿策略=轮数；单仓策略=成交笔数）。 */
    private Integer cycleCount;
    /** 最长连续净亏轮数（轮级风控判据 c6 用）。 */
    private Integer maxLosingCycles;
    /** 平均每轮档数（多腿策略用；单仓策略=1.00）。 */
    private BigDecimal avgLevels;
    private String status;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getDataTimeframe() {
        return dataTimeframe;
    }

    public void setDataTimeframe(String dataTimeframe) {
        this.dataTimeframe = dataTimeframe;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getSpreadMode() {
        return spreadMode;
    }

    public void setSpreadMode(String spreadMode) {
        this.spreadMode = spreadMode;
    }

    public BigDecimal getSpread() {
        return spread;
    }

    public void setSpread(BigDecimal spread) {
        this.spread = spread;
    }

    public BigDecimal getSlippage() {
        return slippage;
    }

    public void setSlippage(BigDecimal slippage) {
        this.slippage = slippage;
    }

    public Boolean getSwapOn() {
        return swapOn;
    }

    public void setSwapOn(Boolean swapOn) {
        this.swapOn = swapOn;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public BigDecimal getWinRate() {
        return winRate;
    }

    public void setWinRate(BigDecimal winRate) {
        this.winRate = winRate;
    }

    public BigDecimal getProfitFactor() {
        return profitFactor;
    }

    public void setProfitFactor(BigDecimal profitFactor) {
        this.profitFactor = profitFactor;
    }

    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }

    public void setMaxDrawdown(BigDecimal maxDrawdown) {
        this.maxDrawdown = maxDrawdown;
    }

    public Integer getTradeCount() {
        return tradeCount;
    }

    public void setTradeCount(Integer tradeCount) {
        this.tradeCount = tradeCount;
    }

    public Integer getAvgHoldSec() {
        return avgHoldSec;
    }

    public void setAvgHoldSec(Integer avgHoldSec) {
        this.avgHoldSec = avgHoldSec;
    }

    public Integer getCycleCount() {
        return cycleCount;
    }

    public void setCycleCount(Integer cycleCount) {
        this.cycleCount = cycleCount;
    }

    public Integer getMaxLosingCycles() {
        return maxLosingCycles;
    }

    public void setMaxLosingCycles(Integer maxLosingCycles) {
        this.maxLosingCycles = maxLosingCycles;
    }

    public BigDecimal getAvgLevels() {
        return avgLevels;
    }

    public void setAvgLevels(BigDecimal avgLevels) {
        this.avgLevels = avgLevels;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}