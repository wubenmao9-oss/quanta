package com.benmao.mt5;

/** 连接配置：本地 Python 服务地址 + 默认品种。 */
public final class Mt5Config {

    /** 本地 Python 服务默认地址。 */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8765";
    /** FxPro 的黄金品种名为 GOLD（不是 XAUUSD）。 */
    public static final String DEFAULT_SYMBOL = "GOLD";

    private final String baseUrl;
    private final String defaultSymbol;

    public Mt5Config(String baseUrl, String defaultSymbol) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.defaultSymbol = defaultSymbol;
    }

    /** 本机默认配置。 */
    public static Mt5Config local() {
        return new Mt5Config(DEFAULT_BASE_URL, DEFAULT_SYMBOL);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String defaultSymbol() {
        return defaultSymbol;
    }
}