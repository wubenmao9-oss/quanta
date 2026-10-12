package com.benmao.quanta.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** sys_state 实体（对应表 sys_state）。 */
public class SysState {

    private Long id;
    private String runMode;
    private Boolean degraded;
    private Long tokenUsedToday;
    private LocalDate tokenDate;
    private Long activeLlmId;
    private Long activeStrategyId;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public void setDegraded(Boolean degraded) {
        this.degraded = degraded;
    }

    public Long getTokenUsedToday() {
        return tokenUsedToday;
    }

    public void setTokenUsedToday(Long tokenUsedToday) {
        this.tokenUsedToday = tokenUsedToday;
    }

    public LocalDate getTokenDate() {
        return tokenDate;
    }

    public void setTokenDate(LocalDate tokenDate) {
        this.tokenDate = tokenDate;
    }

    public Long getActiveLlmId() {
        return activeLlmId;
    }

    public void setActiveLlmId(Long activeLlmId) {
        this.activeLlmId = activeLlmId;
    }

    public Long getActiveStrategyId() {
        return activeStrategyId;
    }

    public void setActiveStrategyId(Long activeStrategyId) {
        this.activeStrategyId = activeStrategyId;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}