package com.benmao.mt5;

import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Quote;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 行情：实时报价 + K 线（对应文档 §6）。 */
public class Mt5MarketService {

    private final Mt5Client client;
    private final Mt5Config config;
    private final Mt5Symbols symbols;

    public Mt5MarketService(Mt5Client client, Mt5Config config, Mt5Symbols symbols) {
        this.client = client;
        this.config = config;
        this.symbols = symbols;
    }

    /** 实时报价（Tick）。symbol 为逻辑名时会先映射成券商名。 */
    public Quote getQuote(String symbol) {
        return client.get("/quote?symbol=" + enc(symbols.resolve(symbol)), Quote.class);
    }

    public Quote getQuote() {
        return getQuote(config.defaultSymbol());
    }

    /** 取最近 count 根 K 线（每根 OHLCV）。timeframe 见 Mt5Config/文档 §6.3。 */
    public List<KlineBar> getKlines(String symbol, String timeframe, int count) {
        String path = "/klines?symbol=" + enc(symbols.resolve(symbol))
                + "&timeframe=" + enc(timeframe) + "&count=" + count;
        return client.getList(path, new TypeReference<List<KlineBar>>() {
        });
    }

    public List<KlineBar> getKlines(String timeframe, int count) {
        return getKlines(config.defaultSymbol(), timeframe, count);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}