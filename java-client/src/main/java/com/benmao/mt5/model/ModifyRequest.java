package com.benmao.mt5.model;

/**
 * 修改持仓 SL/TP 请求体。
 * sl 或 tp 传 null 表示保留原值（避免把原有止盈/止损清空）。
 */
public record ModifyRequest(String symbol, long ticket, Double sl, Double tp) {
    public static ModifyRequest sl(String symbol, long ticket, double sl) {
        return new ModifyRequest(symbol, ticket, sl, null);
    }

    public static ModifyRequest tp(String symbol, long ticket, double tp) {
        return new ModifyRequest(symbol, ticket, null, tp);
    }

    public static ModifyRequest both(String symbol, long ticket, double sl, double tp) {
        return new ModifyRequest(symbol, ticket, sl, tp);
    }
}