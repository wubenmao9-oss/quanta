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
}