package com.benmao.mt5;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名称映射层：逻辑名 → 券商品种名（FxPro 实测黄金真名是 GOLD，不是 XAUUSD）。
 * 用 JDK 自带 HttpServer 打桩 /symbols，不依赖真实 mt5_service。
 */
class Mt5SymbolsTest {

    private HttpServer server;
    private Mt5 mt5;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/symbols", exchange -> {
            byte[] body = "{\"count\":2,\"default\":\"GOLD\",\"symbols\":[\"GOLD\",\"GOLDEUR\"]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        // 故意把默认品种写成 XAUUSD，验证门面会映射成券商的 GOLD
        mt5 = new Mt5(new Mt5Config("http://127.0.0.1:" + server.getAddress().getPort(), "XAUUSD"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("别名映射：XAUUSD / gold / 黄金 都映射为 GOLD；其它品种原样透传")
    void resolveAlias() {
        assertEquals("GOLD", mt5.symbols.resolve("XAUUSD"));
        assertEquals("GOLD", mt5.symbols.resolve("gold"));
        assertEquals("GOLD", mt5.symbols.resolve(" 黄金 "));
        assertEquals("GOLD", mt5.symbols.resolve("GOLD"));
        assertEquals("EURUSD", mt5.symbols.resolve("EURUSD"));
        assertEquals("", mt5.symbols.resolve(""));
        assertNull(mt5.symbols.resolve(null));
    }

    @Test
    @DisplayName("默认品种（配置为 XAUUSD）对外解析为券商名 GOLD")
    void defaultSymbolIsBrokerName() {
        assertEquals("GOLD", mt5.symbols.defaultSymbol());
    }

    @Test
    @DisplayName("用券商真实清单校验品种是否存在：GOLD 在、EURUSD 不在")
    void existsChecksBrokerList() {
        assertTrue(mt5.symbols.exists("GOLD"));
        assertTrue(mt5.symbols.exists("XAUUSD"));
        assertFalse(mt5.symbols.exists("EURUSD"));
        assertFalse(mt5.symbols.exists(""));
    }

    @Test
    @DisplayName("清单查询返回券商品种名")
    void searchReturnsBrokerNames() {
        assertTrue(mt5.symbols.search("GOLD").contains("GOLD"));
    }
}