package com.benmao.quanta.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 交易研判 Agent（LangChain4j {@code @AiService}）。
 *
 * <p>装配要点（设计 §3 / §19）：
 * <ul>
 *   <li>{@code chatModel = "agentChatModel"}：延迟解析到配置层激活的大模型；</li>
 *   <li>{@code tools = "agentMarketTools"}：<b>只挂只读工具</b>；写操作<b>不</b>暴露给模型，
 *       由 Java 在「置信度门控通过 + 动作来自契约」后统一经 {@code TradingService → RiskGuard} 执行，
 *       从根本上杜绝「同一次决策双下单」与「低置信度仍下单」。</li>
 * </ul>
 *
 * <p>返回 {@link Result} 以连同 token 用量一并取回（写入 {@code agent_decision_log.tokens}）。
 * 系统提示词为策略库中的策略 Prompt（每条策略自带），用户消息为本次实时快照与输出契约。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "agentChatModel",
        tools = "agentMarketTools"
)
public interface TradingAgentAi {

    /**
     * 做一次交易研判。
     *
     * @param systemPrompt 策略 Prompt（系统消息）
     * @param userMessage  实时快照 + 输出契约（用户消息）
     * @return 模型输出文本 + token 用量
     */
    @SystemMessage("{{system}}")
    Result<String> decide(@V("system") String systemPrompt, @UserMessage String userMessage);
}
