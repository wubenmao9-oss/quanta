package com.benmao.quanta.bridge;

import com.benmao.mt5.Mt5;
import com.benmao.mt5.Mt5Config;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 桥接层单测：用 JDK 自带 HttpServer 打桩 mt5_service，不依赖真实 MT5（真环境验收见 Mt5GoldClosedLoopIT）。
 */
class Mt5BridgeServiceTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger connectHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        stub("/health", "{\"ok\":true,\"terminal_connected\":true,\"login\":592176689,\"server\":\"FxPro-MT5 Demo\"}");
        stub("/account", "{\"login\":592176689,\"name\":\"demo\",\"server\":\"FxPro-MT5 Demo\",\"currency\":\"USD\","
                + "\"balance\":499.98,\"equity\":499.98,\"margin\":0,\"free_margin\":499.98,\"profit\":0,\"leverage\":500}");
        stub("/quote", "{\"symbol\":\"GOLD\",\"bid\":4194.07,\"ask\":4194.23,\"spread\":16,\"digits\":2,\"time\":1760000000}");
        stub("/klines", "[{\"time\":1760000000,\"open\":4194.0,\"high\":4195.0,\"low\":4193.0,\"close\":4194.5,\"volume\":100}]");
        stub("/positions", "[]");
        stub("/symbols", "{\"count\":1,\"default\":\"GOLD\",\"symbols\":[\"GOLD\"]}");
        server.createContext("/connect", exchange -> {
            connectHits.incrementAndGet();
            byte[] body = "{\"ok\":true,\"terminal_connected\":true,\"login\":592176689,\"server\":\"FxPro-MT5 Demo\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stub(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private Mt5 mt5() {
        return new Mt5(new Mt5Config("http://127.0.0.1:" + port, "GOLD"));
    }

    private Mt5BridgeService bridge(ConfigService configService, boolean autoLogin) {
        return new Mt5BridgeService(mt5(), configService, autoLogin);
    }

    private static ConfigService noActiveConfig() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.activeDecrypted(ConfigType.MT5)).thenReturn(null);
        return configService;
    }

    @Test
    @DisplayName("账户 / 报价 / K线 / 持仓 均能从桥接取到")
    void readApisWork() {
        Mt5BridgeService bridge = bridge(noActiveConfig(), true);

        assertTrue(Boolean.TRUE.equals(bridge.health().get("ok")));
        assertEquals(592176689L, bridge.account().login());
        assertEquals(499.98, bridge.account().freeMargin(), 1e-9);
        assertEquals(4194.23, bridge.quote("XAUUSD").ask(), 1e-9);
        assertEquals(1, bridge.klines("GOLD", "M5", 1).size());
        assertTrue(bridge.positions("GOLD").isEmpty());
        assertEquals("GOLD", bridge.symbols("GOLD").get(0));
    }

    @Test
    @DisplayName("名称映射层：XAUUSD → 券商真实名 GOLD")
    void symbolMapping() {
        Mt5BridgeService bridge = bridge(noActiveConfig(), true);
        assertEquals("GOLD", bridge.resolveSymbol("XAUUSD"));
        assertEquals("GOLD", bridge.resolveSymbol("黄金"));
        assertEquals("GOLD", bridge.defaultSymbol());
        assertEquals("EURUSD", bridge.resolveSymbol("EURUSD"));
    }

    @Test
    @DisplayName("有激活的 MT5 配置时，用库内凭据登录；同一账号只登录一次")
    void logsInWithStoredCredentialsOnce() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.activeDecrypted(ConfigType.MT5)).thenReturn(Map.of(
                "mt5Login", 592176689, "mt5Password", "pwd", "mt5Server", "FxPro-MT5 Demo"));
        Mt5BridgeService bridge = bridge(configService, true);

        bridge.account();
        bridge.quote("GOLD");

        assertEquals(1, connectHits.get(), "同一账号不应重复登录");
    }

    @Test
    @DisplayName("auto-login=false 时不做真实登录，直接用桥接自身会话")
    void autoLoginDisabled() {
        ConfigService configService = mock(ConfigService.class);
        Mt5BridgeService bridge = bridge(configService, false);

        assertEquals(592176689L, bridge.account().login());
        assertEquals(0, connectHits.get());
        verify(configService, never()).activeDecrypted(ConfigType.MT5);
    }

    @Test
    @DisplayName("显式 connect：无激活配置时给出可读提示")
    void connectWithoutConfigIsRejected() {
        Mt5BridgeService bridge = bridge(noActiveConfig(), true);
        BizException e = assertThrows(BizException.class, bridge::connect);
        assertTrue(e.getMessage().contains("没有激活的 MT5 配置"));
    }

    @Test
    @DisplayName("桥接服务未启动：失败原因可读（提示检查 mt5_service）")
    void bridgeDownGivesReadableError() {
        server.stop(0);
        Mt5BridgeService bridge = bridge(noActiveConfig(), true);
        BizException e = assertThrows(BizException.class, bridge::account);
        assertTrue(e.getMessage().contains("mt5_service"));
    }

    @Test
    @DisplayName("登录失败：把桥接返回的 error 归一成可读业务异常")
    void loginFailureIsReadable() {
        server.removeContext("/connect");
        server.createContext("/connect", exchange -> {
            byte[] body = "{\"ok\":false,\"terminal_connected\":false,\"error\":\"MT5 登录失败: (-6, 'Authorization failed')\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        ConfigService configService = mock(ConfigService.class);
        when(configService.activeDecrypted(ConfigType.MT5)).thenReturn(Map.of(
                "mt5Login", 1, "mt5Password", "bad", "mt5Server", "FxPro-MT5 Demo"));
        Mt5BridgeService bridge = bridge(configService, true);

        BizException e = assertThrows(BizException.class, bridge::account);
        assertTrue(e.getMessage().contains("MT5 登录失败"));
        assertTrue(e.getMessage().contains("Authorization failed"));
    }

    @Test
    @DisplayName("重复调用只登录一次（幂等）")
    void loginIsIdempotentAcrossCalls() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.activeDecrypted(ConfigType.MT5)).thenReturn(Map.of(
                "mt5Login", 592176689, "mt5Password", "pwd", "mt5Server", "FxPro-MT5 Demo"));
        Mt5BridgeService bridge = bridge(configService, true);

        bridge.account();
        bridge.positions(null);
        bridge.klines("GOLD", "M5", 1);

        verify(configService, times(1)).activeDecrypted(ConfigType.MT5);
        assertEquals(1, connectHits.get());
    }
}