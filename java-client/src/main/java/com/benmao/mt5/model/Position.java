package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 当前持仓。type 为 "buy"/"sell"。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(
        long ticket,
        String symbol,
        String type,
        double volume,
        @JsonProperty("open_price") double openPrice,
        @JsonProperty("current_price") double currentPrice,
        double sl,
        double tp,
        double profit,
        double swap,
        long magic,
        String comment,
        @JsonProperty("open_time") String openTime
) {
}