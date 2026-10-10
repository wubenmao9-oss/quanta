package com.benmao.mt5;

import com.benmao.mt5.model.CloseRequest;
import com.benmao.mt5.model.MarketOrderRequest;
import com.benmao.mt5.model.ModifyRequest;
import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.SymbolRequest;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/** 交易：开仓 / 平仓 / 设置 TP-SL（对应文档 §3、§4、§5）。 */
public class Mt5TradingService {

    private final Mt5Client client;
    private final Mt5Config config;

    public Mt5TradingService(Mt5Client client, Mt5Config config) {
        this.client = client;
        this.config = config;
    }

    // ---------- 开仓 ----------

    /** 市价买入。sl/tp 为绝对价格，0 表示不设置。 */
    public OrderResult buyMarket(String symbol, double volume, double sl, double tp) {
        return client.post("/order/buy", new MarketOrderRequest(symbol, volume, sl, tp), OrderResult.class);
    }

    public OrderResult buyMarket(double volume) {
        return buyMarket(config.defaultSymbol(), volume, 0.0, 0.0);
    }

    /** 市价卖出。 */
    public OrderResult sellMarket(String symbol, double volume, double sl, double tp) {
        return client.post("/order/sell", new MarketOrderRequest(symbol, volume, sl, tp), OrderResult.class);
    }

    public OrderResult sellMarket(double volume) {
        return sellMarket(config.defaultSymbol(), volume, 0.0, 0.0);
    }

    // ---------- 平仓 ----------

    /** 按 ticket 平单个仓位。 */
    public OrderResult closePosition(String symbol, long ticket) {
        return client.post("/order/close", new CloseRequest(symbol, ticket), OrderResult.class);
    }

    public OrderResult closePosition(long ticket) {
        return closePosition("", ticket);
    }

    /** 平所有持仓（symbol 为空 = 全部品种）。 */
    public List<OrderResult> closeAllPositions(String symbol) {
        return client.postList("/order/close_all", new SymbolRequest(symbol), new TypeReference<List<OrderResult>>() {
        });
    }

    public List<OrderResult> closeAllPositions() {
        return closeAllPositions("");
    }

    // ---------- 设置 / 修改 TP、SL ----------

    /**
     * 修改持仓的 SL/TP。传 null 的字段保持原值（避免把原有 TP/SL 清空）。
     */
    public OrderResult modifySlTp(String symbol, long ticket, Double sl, Double tp) {
        return client.post("/order/modify", new ModifyRequest(symbol, ticket, sl, tp), OrderResult.class);
    }

    /** 只改止损，保留原止盈。 */
    public OrderResult modifySl(String symbol, long ticket, double sl) {
        return modifySlTp(symbol, ticket, sl, null);
    }

    /** 只改止盈，保留原止损。 */
    public OrderResult modifyTp(String symbol, long ticket, double tp) {
        return modifySlTp(symbol, ticket, null, tp);
    }

    // ---------- 挂单 ----------

    /** 撤销所有挂单（symbol 为空 = 全部品种）。 */
    public List<OrderResult> cancelAllPending(String symbol) {
        return client.postList("/order/cancel_all", new SymbolRequest(symbol), new TypeReference<List<OrderResult>>() {
        });
    }

    public List<OrderResult> cancelAllPending() {
        return cancelAllPending("");
    }
}