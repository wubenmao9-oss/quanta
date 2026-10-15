package com.benmao.quanta.cycle;

import java.time.LocalDateTime;

/**
 * 实盘一「轮」（cycle）主表实体（表 {@code martin_cycle}）。
 *
 * <p>一轮 = 一个马丁篮子的完整生命周期：开首腿 → 逆势补腿 → 整轮止盈/止损 → 收尾。
 * 与 {@code agent_session} 为 <b>1:1</b>（{@code sessionId}）。
 */
public class MartinCycle {

    private Long id;
    /** 轮编号（自增序号，从 1 起）。 */
    private Integer cycleNo;
    private String symbol;
    /** BUY/SELL。 */
    private String side;
    /** 子模式：MARTIN（本轮只实现马丁）。 */
    private String mode;
    /** 状态：S2_ENTERED/S3_ADDED/S4_HOLDING/S5_CLOSING/S6_IDLE_LOCK_CYCLE/…/CLOSED。 */
    private String state;
    /** 当前腿数（1 起）。 */
    private Integer level;
    /** 基准（首腿）手数。 */
    private Double baseVolume;
    /** 总手数。 */
    private Double totalVolume;
    /** 加权均价。 */
    private Double avgEntry;
    /** 整轮止盈价（软件口径，由执行器判定平轮）。 */
    private Double basketTp;
    /** 轮初权益（轮级硬止损基准）。 */
    private Double cycleStartEquity;
    /** 关联会话 id（1:1）。 */
    private Long sessionId;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    /** 平轮原因：TP/HARD_SL/DAY_LOCK/TOTAL_LOCK/MANUAL/END/MAX_LEVEL/REGIME_FLIP。 */
    private String closeReason;
    /** 轮净利（已实现）。 */
    private Double realizedPnl;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(Integer cycleNo) {
        this.cycleNo = cycleNo;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Double getBaseVolume() {
        return baseVolume;
    }

    public void setBaseVolume(Double baseVolume) {
        this.baseVolume = baseVolume;
    }

    public Double getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(Double totalVolume) {
        this.totalVolume = totalVolume;
    }

    public Double getAvgEntry() {
        return avgEntry;
    }

    public void setAvgEntry(Double avgEntry) {
        this.avgEntry = avgEntry;
    }

    public Double getBasketTp() {
        return basketTp;
    }

    public void setBasketTp(Double basketTp) {
        this.basketTp = basketTp;
    }

    public Double getCycleStartEquity() {
        return cycleStartEquity;
    }

    public void setCycleStartEquity(Double cycleStartEquity) {
        this.cycleStartEquity = cycleStartEquity;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }

    public Double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(Double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
