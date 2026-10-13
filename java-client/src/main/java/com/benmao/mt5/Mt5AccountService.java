package com.benmao.mt5;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.Position;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 账户与持仓（对应文档 §7）。 */
public class Mt5AccountService {

    private final Mt5Client client;
    private final Mt5Symbols symbols;

    public Mt5AccountService(Mt5Client client, Mt5Symbols symbols) {
        this.client = client;
        this.symbols = symbols;
    }

    /**
     * 账户资金（“还剩多少 U”）。
     * freeMargin = 可用保证金（还能开仓的钱）；equity = 净值；balance = 结余。
     */
    public AccountInfo getAccountInfo() {
        return client.get("/account", AccountInfo.class);
    }

    /** 当前所有持仓。 */
    public List<Position> getPositions() {
        return client.getList("/positions", new TypeReference<List<Position>>() {
        });
    }

    /** 指定品种的持仓（逻辑名会先映射成券商名，如 XAUUSD → GOLD）。 */
    public List<Position> getPositions(String symbol) {
        String q = URLEncoder.encode(symbols.resolve(symbol), StandardCharsets.UTF_8);
        return client.getList("/positions?symbol=" + q, new TypeReference<List<Position>>() {
        });
    }

    /** 按 ticket 查找持仓，找不到返回 null。 */
    public Position findPosition(long ticket) {
        for (Position p : getPositions()) {
            if (p.ticket() == ticket) {
                return p;
            }
        }
        return null;
    }

    /** 指定 [fromEpoch, toEpoch] 区间的成交历史（UTC epoch 秒）；对账用。 */
    public List<Deal> historyDeals(long fromEpoch, long toEpoch) {
        return client.getList("/history/deals?from_ts=" + fromEpoch + "&to_ts=" + toEpoch,
                new TypeReference<List<Deal>>() {
                });
    }
}