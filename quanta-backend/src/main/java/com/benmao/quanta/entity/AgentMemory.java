package com.benmao.quanta.entity;

import java.time.LocalDateTime;

/** agent_memory 实体（对应表 agent_memory）。 */
public class AgentMemory {

    private Long id;
    private String scope;
    private String symbol;
    private Long strategyId;
    private String regime;
    private String summary;
    private String tags;
    private Long sourceTrade;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
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

    public String getRegime() {
        return regime;
    }

    public void setRegime(String regime) {
        this.regime = regime;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Long getSourceTrade() {
        return sourceTrade;
    }

    public void setSourceTrade(Long sourceTrade) {
        this.sourceTrade = sourceTrade;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}