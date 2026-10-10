package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 实时报价（Tick）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Quote(
        String symbol,
        double bid,
        double ask,
        double spread,
        int digits,
        long time
) {
}