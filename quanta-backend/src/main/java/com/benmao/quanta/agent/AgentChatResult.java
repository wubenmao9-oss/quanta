package com.benmao.quanta.agent;

import java.util.List;

/**
 * 自由问答结果（非流式：一次返回完整回答 + 工具调用轨迹）。
 *
 * @param sessionId 会话 id（后续续聊带上此值）
 * @param reply     助手最终回答文本
 * @param model     本次实际使用的模型名（可空）
 * @param tokens    本次 token 用量（可空）
 * @param latencyMs 端到端耗时（毫秒）
 * @param error     fail-open 时的可读错误（可空）；非空时 reply 为兜底说明
 * @param toolCalls 工具调用轨迹（按发生顺序）
 */
public record AgentChatResult(Long sessionId,
                              String reply,
                              String model,
                              Integer tokens,
                              long latencyMs,
                              String error,
                              List<ToolCall> toolCalls) {

    /**
     * 一次工具调用轨迹。
     *
     * @param name      工具名
     * @param arguments 模型给出的入参 JSON（原样回显）
     * @param result    工具返回（已序列化为 JSON 值）
     * @param ok        是否成功
     * @param elapsedMs 耗时
     * @param error     失败原因（可空）
     */
    public record ToolCall(String name,
                           String arguments,
                           Object result,
                           boolean ok,
                           long elapsedMs,
                           String error) {
    }
}
