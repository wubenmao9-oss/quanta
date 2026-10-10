package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** 单根 K 线（OHLCV）。time 为 Unix 秒。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KlineBar(
        long time,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    public Instant instant() {
        return Instant.ofEpochSecond(time);
    }
}