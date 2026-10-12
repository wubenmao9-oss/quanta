package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** trade_plan 实体（对应表 trade_plan）。 */
public class TradePlan {

    private Long id;
    private Long sessionId;
    private String planType;
    private BigDecimal targetPrice;
    private String invalidCond;
    private LocalDateTime expireAt;
    private String status;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getPlanType() {
        return planType;
    }

    public void setPlanType(String planType) {
        this.planType = planType;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(BigDecimal targetPrice) {
        this.targetPrice = targetPrice;
    }

    public String getInvalidCond() {
        return invalidCond;
    }

    public void setInvalidCond(String invalidCond) {
        this.invalidCond = invalidCond;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
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