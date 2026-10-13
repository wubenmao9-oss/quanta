package com.benmao.quanta.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Agent 决策（对设计 §3 返回契约的 Java 侧承载）。
 *
 * @param action     动作枚举
 * @param confidence 置信度 0~1（缺失视为 0，将被门控降级）
 * @param reason     理由（写日志）
 * @param params     动作参数（OPEN：symbol/side/volume/sl/tp；CLOSE/MODIFY：ticket/sl/tp；PLAN：planType/targetPrice/expireAt）
 */
public record AgentDecision(AgentAction action,
                            double confidence,
                            String reason,
                            Map<String, Object> params) {

    public AgentDecision {
        // params 可能含 null 值，故不用 Map.copyOf（其对 null 值抛 NPE）
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        reason = reason == null ? "" : reason;
    }

    /** 降级为 WAIT（置信度门控用）。 */
    public AgentDecision downToWait(String why) {
        return new AgentDecision(AgentAction.WAIT, confidence, why, params);
    }
}
