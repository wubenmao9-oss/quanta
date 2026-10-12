package com.benmao.quanta.entity;

import java.time.LocalDateTime;

/** agent_session 实体（对应表 agent_session）。 */
public class AgentSession {

    private Long id;
    private Integer sessionNo;
    private String sessionType;
    private String symbol;
    private Long strategyId;
    private Long llmConfigId;
    private String status;
    private Long linkedTicket;
    private Integer messageCount;
    private LocalDateTime createTime;
    private LocalDateTime closeTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getSessionNo() {
        return sessionNo;
    }

    public void setSessionNo(Integer sessionNo) {
        this.sessionNo = sessionNo;
    }

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public Long getLlmConfigId() {
        return llmConfigId;
    }

    public void setLlmConfigId(Long llmConfigId) {
        this.llmConfigId = llmConfigId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLinkedTicket() {
        return linkedTicket;
    }

    public void setLinkedTicket(Long linkedTicket) {
        this.linkedTicket = linkedTicket;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(LocalDateTime closeTime) {
        this.closeTime = closeTime;
    }
}