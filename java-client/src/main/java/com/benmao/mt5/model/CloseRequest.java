package com.benmao.mt5.model;

/** 按 ticket 平仓请求体。 */
public record CloseRequest(String symbol, long ticket) {
}