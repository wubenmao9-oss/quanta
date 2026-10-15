package com.benmao.quanta.agent;

import java.util.List;

/**
 * 自由问答请求（{@code POST /api/agent/chat}）。
 *
 * <p>多轮上下文由服务端按 {@code sessionId} 从 {@code agent_message} 表拼接，客户端只需带同一个
 * {@code sessionId} 即可续聊；{@code sessionId} 为空则新建一条 MANUAL 会话。
 *
 * @param sessionId 续聊会话 id（可空=新建）
 * @param message   本轮用户消息（必填）
 * @param symbol    关注的品种（可空=默认 GOLD），用于给模型上下文
 * @param timeframe 关注的周期（可空）
 * @param persona   人设：GENERAL（通用交易助手，默认）/ STRATEGY（策略设计助手）
 * @param context   附加上下文文本（可空），如"AI 策略对话框"里待打磨的策略草稿
 * @param history   客户端可选附带的近几轮历史（role=user/assistant），与库内消息合并取最近者
 */
public record AgentChatRequest(Long sessionId,
                               String message,
                               String symbol,
                               String timeframe,
                               String persona,
                               String context,
                               List<ChatTurn> history) {

    /** 一轮对话（role: user / assistant）。 */
    public record ChatTurn(String role, String content) {
    }
}
