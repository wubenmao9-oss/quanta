package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 账户资金信息。
 * balance=结余(本金)  equity=净值  freeMargin=可用保证金(还能开仓的钱)  profit=浮动盈亏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfo(
        long login,
        String name,
        String server,
        String currency,
        double balance,
        double equity,
        double margin,
        @JsonProperty("free_margin") double freeMargin,
        double profit,
        int leverage
) {
}