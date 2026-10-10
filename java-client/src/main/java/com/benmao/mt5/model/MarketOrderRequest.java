package com.benmao.mt5.model;

/** 市价开仓请求体。sl/tp 为绝对价格，0 表示不设置。 */
public record MarketOrderRequest(String symbol, double volume, double sl, double tp) {
}