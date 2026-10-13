package com.benmao.quanta.agent;

/**
 * 一次 Agent 研判请求（{@code POST /api/agent/decide} 的请求体）。
 *
 * <p>字段全部可空：缺省时用 {@link AgentProperties} 的默认值（品种/周期）与触发源默认 {@code T6}（人工唤醒）。
 *
 * @param symbol      品种（券商真实名或逻辑名），可空=默认品种
 * @param timeframe   K 线周期，可空=默认周期
 * @param strategyId  指定策略 id，可空=取第一条 ACTIVE 策略
 * @param sessionId   复用已有会话 id，可空=新建会话
 * @param triggerType 触发源（T1~T8），可空=人工唤醒 T6
 * @param reason      唤醒原因（写入快照与日志，便于审计）
 */
public record AgentDecideRequest(String symbol,
                                 String timeframe,
                                 Long strategyId,
                                 Long sessionId,
                                 String triggerType,
                                 String reason) {
}
