package com.benmao.quanta.cycle;

import java.time.LocalDateTime;

/**
 * cycle 执行器锁档设置实体（表 {@code cycle_setting}，单行 {@code setting_key=DEFAULT}）。
 *
 * <p>语义：字段为 {@code null} → 回落 {@code quanta.strategy.basket.locks} 的配置默认；
 * 数值 {@code 0} → 该档<b>不启用</b>（即「无上限」）；正数 → 以该百分比为阈值。
 * 前端「策略锁档」面板据此读写，人工可随时放宽/收紧，无需改代码。
 */
public class CycleSetting {

    private Long id;
    /** 设置键（当前仅 DEFAULT，单行）。 */
    private String settingKey;
    /** 单轮最大亏损%（0 或 null = 不启用）。 */
    private Double maxSingleLossPct;
    /** 单日最大亏损%（0 或 null = 不启用）。 */
    private Double maxDailyLossPct;
    /** 累计最大亏损%（0 或 null = 不启用）。 */
    private Double maxTotalLossPct;
    /** 触发锁后的锁定时长（小时；null → 回落配置默认）。 */
    private Integer lockHours;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public Double getMaxSingleLossPct() {
        return maxSingleLossPct;
    }

    public void setMaxSingleLossPct(Double maxSingleLossPct) {
        this.maxSingleLossPct = maxSingleLossPct;
    }

    public Double getMaxDailyLossPct() {
        return maxDailyLossPct;
    }

    public void setMaxDailyLossPct(Double maxDailyLossPct) {
        this.maxDailyLossPct = maxDailyLossPct;
    }

    public Double getMaxTotalLossPct() {
        return maxTotalLossPct;
    }

    public void setMaxTotalLossPct(Double maxTotalLossPct) {
        this.maxTotalLossPct = maxTotalLossPct;
    }

    public Integer getLockHours() {
        return lockHours;
    }

    public void setLockHours(Integer lockHours) {
        this.lockHours = lockHours;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
