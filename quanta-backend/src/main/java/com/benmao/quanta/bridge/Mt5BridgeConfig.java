package com.benmao.quanta.bridge;

import com.benmao.mt5.Mt5;
import com.benmao.mt5.Mt5Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 桥接层装配：把已跑通的 MT5 Java 门面（java-client）纳入 Spring 容器。
 *
 * <p>地址与品种一律走配置（{@code quanta.mt5.*}），不在代码里写死。
 */
@Configuration
public class Mt5BridgeConfig {

    @Bean
    public Mt5Config mt5Config(@Value("${quanta.mt5.base-url:http://127.0.0.1:8765}") String baseUrl,
                               @Value("${quanta.mt5.symbol:GOLD}") String symbol) {
        return new Mt5Config(baseUrl, symbol);
    }

    @Bean
    public Mt5 mt5(Mt5Config config) {
        return new Mt5(config);
    }
}