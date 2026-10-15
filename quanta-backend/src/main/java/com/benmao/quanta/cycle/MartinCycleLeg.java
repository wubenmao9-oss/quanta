package com.benmao.quanta.cycle;

import java.time.LocalDateTime;

/**
 * 实盘一轮中的一「腿」实体（表 {@code martin_cycle_leg}）。
 *
 * <p>每条记录对应券商侧一个真实持仓（{@code ticket}）。整轮止盈价落在轮主表
 * {@link MartinCycle#getBasketTp()}，故本表不单独存 TP（腿级 TP 为 null）。
 */
public class MartinCycleLeg {

    private Long id;
    private Long cycleId;
    /** 腿序号（1 起）。 */
    private Integer level;
    /** 券商持仓票号。 */
    private Long ticket;
    /** BUY/SELL。 */
    private String side;
    private Double volume;
    private Double entryPrice;
    /** 该腿真实挂单止损价。 */
    private Double sl;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private Double closePrice;
    private Double realizedPnl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCycleId() {
        return cycleId;
    }

    public void setCycleId(Long cycleId) {
        this.cycleId = cycleId;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Long getTicket() {
        return ticket;
    }

    public void setTicket(Long ticket) {
        this.ticket = ticket;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public Double getVolume() {
        return volume;
    }

    public void setVolume(Double volume) {
        this.volume = volume;
    }

    public Double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(Double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public Double getSl() {
        return sl;
    }

    public void setSl(Double sl) {
        this.sl = sl;
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

    public Double getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
    }

    public Double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(Double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }
}
