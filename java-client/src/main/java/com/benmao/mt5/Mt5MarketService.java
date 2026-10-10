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

    public Mt5MarketService(Mt5Client client, Mt5Config config) {
        this.client = client;
        this.config = config;
    }

    /** 实时报价（Tick）。 */
    public Quote getQuote(String symbol) {
        return client.get("/quote?symbol=" + enc(symbol), Quote.class);
    }

    public Quote getQuote() {
        return getQuote(config.defaultSymbol());
    }

    /** 取最近 count 根 K 线（每根 OHLCV）。timeframe 见 Mt5Config/文档 §6.3。 */
    public List<KlineBar> getKlines(String symbol, String timeframe, int count) {
        String path = "/klines?symbol=" + enc(symbol) + "&timeframe=" + enc(timeframe) + "&count=" + count;
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