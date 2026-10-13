package com.benmao.quanta.agent;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把大模型返回的文本解析为 {@link AgentDecision}（设计 §3 返回契约）。
 *
 * <p>工程考虑：模型输出常带解释性文字或 ```json 代码围栏，故解析需<b>容错</b>：
 * 先剥离围栏，再抓取首个 <code>{</code> 到末个 <code>}</code> 之间的 JSON 对象。
 *
 * <p>防幻觉：动作名先经 {@link AgentAction#parse} 校验，非法即抛出并回执合法枚举；
 * 缺失 {@code confidence} 视为 0（后续会被门控降级为 WAIT，fail-closed）。
 */
@Component
public class AgentDecisionParser {

    private final ObjectMapper mapper;

    public AgentDecisionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 解析模型输出；无法解析/动作非法 → 抛业务异常（调用方据此 fail-closed 降级 WAIT）。 */
    public AgentDecision parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "模型未返回内容；合法动作：" + AgentAction.names(), 400);
        }
        String json = extractJson(rawText);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "无法解析模型决策为 JSON（合法动作：" + AgentAction.names() + "）：" + e.getMessage(), 400);
        }
        if (node == null || !node.isObject()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "模型决策不是 JSON 对象；合法动作：" + AgentAction.names(), 400);
        }

        JsonNode actionNode = node.get("action");
        if (actionNode == null || actionNode.isNull() || actionNode.asText().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "模型决策缺少 action 字段；合法动作：" + AgentAction.names(), 400);
        }
        AgentAction action = AgentAction.parse(actionNode.asText());

        double confidence = node.hasNonNull("confidence") ? clamp(node.get("confidence").asDouble(0.0)) : 0.0;
        String reason = node.hasNonNull("reason") ? node.get("reason").asText() : "";

        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = node.get("params");
        if (paramsNode != null && paramsNode.isObject()) {
            params = mapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {
            });
        }
        return new AgentDecision(action, confidence, reason, params);
    }

    /** 剥离 ```json 围栏并截取首个对象。 */
    private static String extractJson(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "模型决策中未找到 JSON 对象；合法动作：" + AgentAction.names(), 400);
        }
        return s.substring(start, end + 1);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
