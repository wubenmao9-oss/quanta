package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** strategy 实体（对应表 strategy）。 */
public class Strategy {

    private Long id;
    private String name;
    private String prompt;
    private Integer version;
    private String status;
    private Long parentId;
    private String origin;
    private Boolean approved;
    private String regimeType;
    private BigDecimal targetRr;
    private BigDecimal expectedWinRate;
    private String indicatorParams;
    private String description;
    /** 策略族（SINGLE_LEG/BASKET）：非表字段，由目录服务按规则键回填，供前端展示族标签。 */
    private String family;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Boolean getApproved() {
        return approved;
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public String getRegimeType() {
        return regimeType;
    }

    public void setRegimeType(String regimeType) {
        this.regimeType = regimeType;
    }

    public BigDecimal getTargetRr() {
        return targetRr;
    }

    public void setTargetRr(BigDecimal targetRr) {
        this.targetRr = targetRr;
    }

    public BigDecimal getExpectedWinRate() {
        return expectedWinRate;
    }

    public void setExpectedWinRate(BigDecimal expectedWinRate) {
        this.expectedWinRate = expectedWinRate;
    }

    public String getIndicatorParams() {
        return indicatorParams;
    }

    public void setIndicatorParams(String indicatorParams) {
        this.indicatorParams = indicatorParams;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
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