package com.benmao.mt5;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Position;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 账户与持仓（对应文档 §7）。 */
public class Mt5AccountService {

    private final Mt5Client client;

    public Mt5AccountService(Mt5Client client) {
        this.client = client;
    }

    /** 服务/终端连通性。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        return client.get("/health", Map.class);
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

    /** 指定品种的持仓。 */
    public List<Position> getPositions(String symbol) {
        String q = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
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
}