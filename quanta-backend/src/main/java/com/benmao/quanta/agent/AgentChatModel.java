package com.benmao.quanta.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 把 {@link ChatLanguageModel} 延迟解析到「配置层激活的大模型」（bean 名 {@code agentChatModel}）。
 *
 * <p>为何不直接注入 {@code OpenAiChatModel}：模型凭据在库里（M1），启动时可能尚未配置或密钥未就绪；
 * 若启动即构建会「无配置则起不来」。本类在<b>每次调用时</b>通过 {@link AgentModelFactory} 解析真实模型，
 * 从而保证应用可正常启动、Agent 报错可读（"没有激活的 LLM 配置"）。
 */
@Component("agentChatModel")
public class AgentChatModel implements ChatLanguageModel {

    private final AgentModelFactory factory;

    public AgentChatModel(AgentModelFactory factory) {
        this.factory = factory;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return factory.current().generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return factory.current().generate(messages, toolSpecifications);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return factory.current().chat(request);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return factory.current().supportedCapabilities();
    }
}
