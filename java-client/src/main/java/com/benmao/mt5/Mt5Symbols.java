package com.benmao.mt5;

import com.benmao.mt5.model.SymbolList;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 名称映射层：把「逻辑品种名」映射为券商的真实品种名（一期只做黄金）。
 *
 * <p>FxPro 实测：黄金的真实名是 {@code GOLD}，<b>不是</b> {@code XAUUSD}。
 * {@link #resolve(String)} 是纯本地映射（不做 I/O），保证下单路径确定且低延迟；
 * {@link #search(String)} / {@link #exists(String)} 才查券商真实清单，用于校验。
 */
public class Mt5Symbols {

    /** 逻辑名 → 券商真实名。未登记的品种按原样透传（架构预留多品种）。 */
    private static final Map<String, String> ALIAS = Map.of(
            "GOLD", "GOLD",
            "XAUUSD", "GOLD",
            "GOLDUSD", "GOLD",
            "XAU", "GOLD",
            "GOLD.X", "GOLD",
            "黄金", "GOLD");

    private final Mt5Client client;
    private final Mt5Config config;

    public Mt5Symbols(Mt5Client client, Mt5Config config) {
        this.client = client;
        this.config = config;
    }

    /** 逻辑名 → 券商品种名；空值原样返回，未登记的原样透传。 */
    public String resolve(String logical) {
        if (logical == null || logical.isBlank()) {
            return logical;
        }
        String trimmed = logical.trim();
        return ALIAS.getOrDefault(trimmed.toUpperCase(Locale.ROOT), trimmed);
    }

    /** 一期默认品种（GOLD）的券商名。 */
    public String defaultSymbol() {
        return resolve(config.defaultSymbol());
    }

    /** 查券商品种清单，可按关键字过滤（空 = 全部，受桥接侧 limit 限制）。 */
    public List<String> search(String query) {
        SymbolList list = client.get("/symbols?query=" + enc(query == null ? "" : query), SymbolList.class);
        return list.symbols() == null ? List.of() : list.symbols();
    }

    /** 券商是否真有该品种（把逻辑名映射后再校验，防止把逻辑名直接丢给券商）。 */
    public boolean exists(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String brokerSymbol = resolve(symbol);
        for (String name : search(brokerSymbol)) {
            if (name.equalsIgnoreCase(brokerSymbol)) {
                return true;
            }
        }
        return false;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}