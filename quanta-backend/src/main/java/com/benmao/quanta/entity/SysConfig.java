package com.benmao.quanta.entity;

import java.time.LocalDateTime;

/**
 * 系统配置实体（对应表 sys_config）。
 *
 * <p>字段靠 MyBatis 的 map-underscore-to-camel-case 从 snake_case 列名自动映射：
 * config_type → configType、config_json → configJson、is_active → isActive、tested_at → testedAt。
 *
 * <p>{@code configJson} 为 AES 密文，仅在 Service 内部使用，**绝不出现在对外响应里**。
 */
public class SysConfig {

    private Long id;
    private String configType;
    private String name;
    private String configJson;
    private Boolean isActive;
    private String testStatus;
    private LocalDateTime testedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigType() {
        return configType;
    }

    public void setConfigType(String configType) {
        this.configType = configType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public String getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
    }

    public LocalDateTime getTestedAt() {
        return testedAt;
    }

    public void setTestedAt(LocalDateTime testedAt) {
        this.testedAt = testedAt;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}