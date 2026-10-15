package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** risk_config 实体（对应表 risk_config）。 */
public class RiskConfig {

    private Long id;
    private BigDecimal fixedVolume;
    private BigDecimal maxVolume;
    private Integer maxPositions;
    private BigDecimal maxDailyLoss;
    private BigDecimal maxRiskPct;
    private BigDecimal minRr;
    private BigDecimal minSlSpreadMult;
    private Integer maxConsecutiveLoss;
    private Integer cooldownMinutes;
    private BigDecimal spreadLimit;
    private String symbolWhitelist;
    private LocalDateTime updateTime;

    // ---------------- GRID 专用档（多腿/马丁执行器用；仅放宽 R2/R3/R4/R15，其余仍共用同列以保持硬约束） ----------------

    /** GRID 档单笔手数上限（覆盖 maxVolume；须 ≥ 最高腿手数）。 */
    private BigDecimal gridMaxVolume;
    /** GRID 档最大持仓数（覆盖 maxPositions；须 ≥ 最大腿数）。 */
    private Integer gridMaxPositions;
    /** GRID 档盈亏比地板（覆盖 minRr）。 */
    private BigDecimal gridMinRr;
    /**
     * GRID 档单笔风险预算（占净值百分比，覆盖 maxRiskPct → R4）。
     *
     * <p>马丁单腿带「灾难兜底 SL」（默认 5.00USD），沿用 DEFAULT 档 2% 会整腿被 R4 拒单，故单独放宽。
     */
    private BigDecimal gridMaxRiskPct;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getFixedVolume() {
        return fixedVolume;
    }

    public void setFixedVolume(BigDecimal fixedVolume) {
        this.fixedVolume = fixedVolume;
    }

    public BigDecimal getMaxVolume() {
        return maxVolume;
    }

    public void setMaxVolume(BigDecimal maxVolume) {
        this.maxVolume = maxVolume;
    }

    public Integer getMaxPositions() {
        return maxPositions;
    }

    public void setMaxPositions(Integer maxPositions) {
        this.maxPositions = maxPositions;
    }

    public BigDecimal getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(BigDecimal maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public BigDecimal getMaxRiskPct() {
        return maxRiskPct;
    }

    public void setMaxRiskPct(BigDecimal maxRiskPct) {
        this.maxRiskPct = maxRiskPct;
    }

    public BigDecimal getMinRr() {
        return minRr;
    }

    public void setMinRr(BigDecimal minRr) {
        this.minRr = minRr;
    }

    public BigDecimal getMinSlSpreadMult() {
        return minSlSpreadMult;
    }

    public void setMinSlSpreadMult(BigDecimal minSlSpreadMult) {
        this.minSlSpreadMult = minSlSpreadMult;
    }

    public Integer getMaxConsecutiveLoss() {
        return maxConsecutiveLoss;
    }

    public void setMaxConsecutiveLoss(Integer maxConsecutiveLoss) {
        this.maxConsecutiveLoss = maxConsecutiveLoss;
    }

    public Integer getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(Integer cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public BigDecimal getSpreadLimit() {
        return spreadLimit;
    }

    public void setSpreadLimit(BigDecimal spreadLimit) {
        this.spreadLimit = spreadLimit;
    }

    public String getSymbolWhitelist() {
        return symbolWhitelist;
    }

    public void setSymbolWhitelist(String symbolWhitelist) {
        this.symbolWhitelist = symbolWhitelist;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public BigDecimal getGridMaxVolume() {
        return gridMaxVolume;
    }

    public void setGridMaxVolume(BigDecimal gridMaxVolume) {
        this.gridMaxVolume = gridMaxVolume;
    }

    public Integer getGridMaxPositions() {
        return gridMaxPositions;
    }

    public void setGridMaxPositions(Integer gridMaxPositions) {
        this.gridMaxPositions = gridMaxPositions;
    }

    public BigDecimal getGridMinRr() {
        return gridMinRr;
    }

    public void setGridMinRr(BigDecimal gridMinRr) {
        this.gridMinRr = gridMinRr;
    }

    public BigDecimal getGridMaxRiskPct() {
        return gridMaxRiskPct;
    }

    public void setGridMaxRiskPct(BigDecimal gridMaxRiskPct) {
        this.gridMaxRiskPct = gridMaxRiskPct;
    }
}