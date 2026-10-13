package com.benmao.quanta.agent;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按「配置层激活的大模型配置」构建 {@link ChatLanguageModel}（设计 §15）。
 *
 * <p>大模型凭据由 M1 配置层落库（AES 密文），故本工厂在<b>调用时</b>读取激活配置构建模型，
 * 而非启动时依赖环境变量；按（baseUrl|model|key）缓存，避免每次决策重建客户端。
 *
 * <p>一期仅支持 {@code interfaceType = OPENAI_COMPAT}（DeepSeek 等兼容 OpenAI 协议的服务）。
 */
@Component
public class AgentModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentModelFactory.class);

    private static final String SUPPORTED_INTERFACE = "OPENAI_COMPAT";
    private static final int MAX_CACHE = 8;

    private final ConfigService configService;
    private final AgentProperties props;
    private final ConcurrentMap<String, ChatLanguageModel> cache = new ConcurrentHashMap<>();

    private volatile String currentModelName;

    public AgentModelFactory(ConfigService configService, AgentProperties props) {
        this.configService = configService;
        this.props = props;
    }

    /** 当前激活配置对应的模型；无激活配置 → 快速失败。 */
    public ChatLanguageModel current() {
        Resolved r = resolve();
        currentModelName = r.modelName();
        if (cache.size() > MAX_CACHE) {
            cache.clear();
        }
        return cache.computeIfAbsent(r.key(), k -> build(r));
    }

    /** 最近一次解析出的模型名（供决策日志记录）。 */
    public String currentModelName() {
        return currentModelName;
    }

    private ChatLanguageModel build(Resolved r) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(r.baseUrl())
                .apiKey(r.apiKey())
                .modelName(r.modelName())
                .timeout(Duration.ofSeconds(props.getModelTimeoutSeconds()))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false);
        if (props.getMaxTokens() != null) {
            builder.maxTokens(props.getMaxTokens());
        }
        log.info("构建大模型客户端: baseUrl={}, model={}", r.baseUrl(), r.modelName());
        return builder.build();
    }

    private Resolved resolve() {
        Map<String, Object> cfg = configService.activeDecrypted(ConfigType.LLM);
        if (cfg == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "没有激活的 LLM 配置，无法调用大模型；请先在配置层保存并激活一条 LLM 配置", 400);
        }
        String interfaceType = str(cfg, "interfaceType");
        if (interfaceType != null && !interfaceType.isBlank()
                && !SUPPORTED_INTERFACE.equalsIgnoreCase(interfaceType)) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "不支持的大模型接口类型：" + interfaceType + "（一期仅支持 " + SUPPORTED_INTERFACE + "）", 400);
        }
        String baseUrl = str(cfg, "baseUrl");
        String apiKey = str(cfg, "apiKey");
        String modelName = str(cfg, "modelName");
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "激活的 LLM 配置缺少 baseUrl / apiKey / modelName，无法调用大模型", 400);
        }
        String key = baseUrl + "|" + modelName + "|" + Integer.toHexString(apiKey.hashCode());
        return new Resolved(key, baseUrl, apiKey, modelName);
    }

    private static String str(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record Resolved(String key, String baseUrl, String apiKey, String modelName) {
    }
}
