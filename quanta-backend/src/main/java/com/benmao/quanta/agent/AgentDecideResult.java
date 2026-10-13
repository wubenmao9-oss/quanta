package com.benmao.quanta.agent;

import java.util.Map;

/**
 * 一次 Agent 研判的回执（对 {@code agent_decision_log} 一行的可读映射 + 执行结果）。
 *
 * <p>关键区分：
 * <ul>
 *   <li>{@code modelAction} 是模型输出的<b>原始</b>动作；{@code action} 是经<b>置信度门控</b>后的最终动作；</li>
 *   <li>{@code downgraded=true} 表示因 {@code confidence < 阈值} 被强制降级为 {@code WAIT}；</li>
 *   <li>{@code executed} 表示是否真的经 {@code TradingService → RiskGuard} 下达了写操作；</li>
 *   <li>{@code llmError} 非空表示大模型调用/解析失败（fail-closed，已降级 WAIT 并照样记日志）。</li>
 * </ul>
 */
public record AgentDecideResult(Long decisionLogId,
                                Long sessionId,
                                String traceId,
                                String triggerType,
                                Long strategyId,
                                String strategyName,
                                Integer promptVersion,
                                String model,
                                AgentAction action,
                                AgentAction modelAction,
                                double confidence,
                                boolean downgraded,
                                String reason,
                                Map<String, Object> params,
                                boolean executed,
                                String executionError,
                                Long ticket,
                                Integer tokens,
                                Integer latencyMs,
                                String llmError) {
}
