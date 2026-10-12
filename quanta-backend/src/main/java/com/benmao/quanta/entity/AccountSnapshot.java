package com.benmao.quanta.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** account_snapshot 实体（对应表 account_snapshot）。 */
public class AccountSnapshot {

    private Long id;
    private Long login;
    private BigDecimal balance;
    private BigDecimal equity;
    private BigDecimal margin;
    private BigDecimal freeMargin;
    private BigDecimal profit;
    private String currency;
    private LocalDateTime snapshotTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLogin() {
        return login;
    }

    public void setLogin(Long login) {
        this.login = login;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getEquity() {
        return equity;
    }

    public void setEquity(BigDecimal equity) {
        this.equity = equity;
    }

    public BigDecimal getMargin() {
        return margin;
    }

    public void setMargin(BigDecimal margin) {
        this.margin = margin;
    }

    public BigDecimal getFreeMargin() {
        return freeMargin;
    }

    public void setFreeMargin(BigDecimal freeMargin) {
        this.freeMargin = freeMargin;
    }

    public BigDecimal getProfit() {
        return profit;
    }

    public void setProfit(BigDecimal profit) {
        this.profit = profit;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDateTime getSnapshotTime() {
        return snapshotTime;
    }

    public void setSnapshotTime(LocalDateTime snapshotTime) {
        this.snapshotTime = snapshotTime;
    }
}