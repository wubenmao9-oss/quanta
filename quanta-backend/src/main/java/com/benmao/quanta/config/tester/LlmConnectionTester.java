package com.benmao.quanta.config.tester;

import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.config.dto.ConfigTestResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 连接测试：用给定配置真实发一次最小调用，回复非空即成功。
 *
 * <p>一期仅支持 {@code interfaceType = OPENAI_COMPAT}（DeepSeek 等兼容 OpenAI 协议的服务）。
 * 传空则默认按 OPENAI_COMPAT 处理。
 */
@Component
public class LlmConnectionTester implements ConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(LlmConnectionTester.class);

    private static final String SUPPORTED_INTERFACE = "OPENAI_COMPAT";
    private static final String PING_PROMPT = "ping";

    @Override
    public ConfigType type() {
        return ConfigType.LLM;
    }

    @Override
    public ConfigTestResult test(Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        Map<String, Object> detail = new LinkedHashMap<>();

        String interfaceType = Payloads.str(payload, "interfaceType");
        if (interfaceType != null && !interfaceType.isBlank()
                && !SUPPORTED_INTERFACE.equalsIgnoreCase(interfaceType)) {
            return fail("不支持的接口类型: " + interfaceType + "（一期仅支持 " + SUPPORTED_INTERFACE + "）", start, detail);
        }

        String baseUrl = Payloads.str(payload, "baseUrl");
        String modelName = Payloads.str(payload, "modelName");
        String apiKey = Payloads.str(payload, "apiKey");
        detail.put("baseUrl", baseUrl);
        detail.put("modelName", modelName);

        if (isBlank(baseUrl) || isBlank(modelName) || isBlank(apiKey)) {
            return fail("baseUrl / modelName / apiKey 均不能为空", start, detail);
        }

        try {
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(20))
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            String reply = model.generate(PING_PROMPT);
            boolean ok = reply != null && !reply.isBlank();
            return new ConfigTestResult(ok,
                    ok ? "大模型调用成功（模型 " + modelName + "）" : "大模型返回为空",
                    System.currentTimeMillis() - start, detail);
        } catch (Exception e) {
            log.warn("LLM 测试调用失败: {}", e.getMessage());
            return fail("大模型调用失败: " + e.getMessage(), start, detail);
        }
    }

    private ConfigTestResult fail(String message, long start, Map<String, Object> detail) {
        return new ConfigTestResult(false, message, System.currentTimeMillis() - start, detail);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}