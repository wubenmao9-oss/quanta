package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** backtest_trade 实体（对应表 backtest_trade）。 */
public class BacktestTrade {

    private Long id;
    private Long runId;
    private LocalDateTime openTime;
    private LocalDateTime closeTime;
    private String cmd;
    private BigDecimal volume;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    private BigDecimal sl;
    private BigDecimal tp;
    private BigDecimal profit;
    private BigDecimal swap;
    private Integer holdSec;
    /** 平仓原因（TP/SL/HARD_SL/END/DAY_LOCK/TOTAL_LOCK/MAX_LEVEL）。 */
    private String exitReason;
    /** 所属轮编号（多腿策略；单仓策略为 0）。 */
    private Integer cycleNo;
    /** 该腿在轮内序号（多腿策略；单仓策略为 0）。 */
    private Integer level;
    /** 引擎类型（SINGLE_LEG/MARTIN/GRID/LEGACY/STORED）。 */
    private String engineKind;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public LocalDateTime getOpenTime() {
        return openTime;
    }

    public void setOpenTime(LocalDateTime openTime) {
        this.openTime = openTime;
    }

    public LocalDateTime getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(LocalDateTime closeTime) {
        this.closeTime = closeTime;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getSl() {
        return sl;
    }

    public void setSl(BigDecimal sl) {
        this.sl = sl;
    }

    public BigDecimal getTp() {
        return tp;
    }

    public void setTp(BigDecimal tp) {
        this.tp = tp;
    }

    public BigDecimal getProfit() {
        return profit;
    }

    public void setProfit(BigDecimal profit) {
        this.profit = profit;
    }

    public BigDecimal getSwap() {
        return swap;
    }

    public void setSwap(BigDecimal swap) {
        this.swap = swap;
    }

    public Integer getHoldSec() {
        return holdSec;
    }

    public void setHoldSec(Integer holdSec) {
        this.holdSec = holdSec;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public Integer getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(Integer cycleNo) {
        this.cycleNo = cycleNo;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getEngineKind() {
        return engineKind;
    }

    public void setEngineKind(String engineKind) {
        this.engineKind = engineKind;
    }
}