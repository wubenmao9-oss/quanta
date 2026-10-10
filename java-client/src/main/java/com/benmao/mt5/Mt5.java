package com.benmao.mt5;

/**
 * 统一入口（门面）：一次性拿到账户 / 行情 / 交易三个服务。
 *
 * <pre>{@code
 * Mt5 mt5 = new Mt5();                       // 默认 http://127.0.0.1:8765 + GOLD
 * AccountInfo acc = mt5.account.getAccountInfo();
 * OrderResult r = mt5.trading.buyMarket(0.01);
 * }</pre>
 */
public class Mt5 {

    private final Mt5Config config;
    private final Mt5Client client;

    public final Mt5AccountService account;
    public final Mt5MarketService market;
    public final Mt5TradingService trading;

    public Mt5() {
        this(Mt5Config.local());
    }

    public Mt5(Mt5Config config) {
        this.config = config;
        this.client = new Mt5Client(config);
        this.account = new Mt5AccountService(client);
        this.market = new Mt5MarketService(client, config);
        this.trading = new Mt5TradingService(client, config);
    }

    public Mt5Config config() {
        return config;
    }
}