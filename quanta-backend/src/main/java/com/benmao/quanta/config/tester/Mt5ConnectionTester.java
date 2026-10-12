package com.benmao.quanta.config.tester;

import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.config.dto.ConfigTestResult;
import com.benmao.mt5.Mt5Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MT5 连接测试：探活本地 mt5_service 的 {@code GET /health}。
 *
 * <p><b>语义说明</b>：本测试验证「桥接服务在线 + MT5 终端已连接」，
 * <b>不校验 sys_config 里存的账号密码</b>——真实凭据登录留到 M3 扩展桥接。
 * 判定成功的条件是 {@code ok == true && terminal_connected == true}。
 */
@Component
public class Mt5ConnectionTester implements ConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(Mt5ConnectionTester.class);

    private final String baseUrl;
    private final Duration timeout;

    public Mt5ConnectionTester(@Value("${quanta.mt5.base-url:http://127.0.0.1:8765}") String baseUrl,
                               @Value("${quanta.mt5.health-timeout-ms:5000}") long timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public ConfigType type() {
        return ConfigType.MT5;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConfigTestResult test(Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("baseUrl", baseUrl);
        try {
            Mt5Client client = new Mt5Client(baseUrl, timeout);
            Map<String, Object> health = client.get("/health", Map.class);
            boolean ok = Boolean.TRUE.equals(health.get("ok"))
                    && Boolean.TRUE.equals(health.get("terminal_connected"));
            detail.put("terminalConnected", health.get("terminal_connected"));
            detail.put("login", health.get("login"));
            detail.put("server", health.get("server"));
            String message = ok
                    ? "MT5 桥接与终端在线"
                    : "MT5 未就绪：" + health.getOrDefault("error", "terminal_connected=" + health.get("terminal_connected"));
            return new ConfigTestResult(ok, message, System.currentTimeMillis() - start, detail);
        } catch (Exception e) {
            log.warn("MT5 探活失败: {}", e.getMessage());
            return new ConfigTestResult(false,
                    "MT5 探活失败（mt5_service 是否已启动？）: " + e.getMessage(),
                    System.currentTimeMillis() - start, detail);
        }
    }
}