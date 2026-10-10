package com.benmao.mt5.model;

/** 批量操作请求体（平所有仓 / 撤所有挂单）；symbol 为空表示全部品种。 */
public record SymbolRequest(String symbol) {
}