package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 延迟解析聊天模型单测（M10）：无激活配置时报错可读、有配置时委托真实模型。
 *
 * <p>该设计保证「应用可在尚未配置大模型时正常启动」，把配置缺失推迟到调用期快速失败。
 */
class AgentChatModelTest {

    private final AgentModelFactory factory = mock(AgentModelFactory.class);
    private final AgentChatModel model = new AgentChatModel(factory);

    @Test
    @DisplayName("无激活 LLM 配置 → 抛可读业务异常（而不是 NPE/启动失败）")
    void noConfigFailsReadably() {
        when(factory.current()).thenThrow(new BizException("没有激活的 LLM 配置，无法调用大模型；请先在配置层保存并激活一条 LLM 配置"));

        List<ChatMessage> messages = List.<ChatMessage>of(UserMessage.from("hi"));
        BizException e = assertThrows(BizException.class, () -> model.generate(messages));
        assertTrue(e.getMessage().contains("LLM"));
    }

    @Test
    @DisplayName("有配置 → 委托给工厂解析出的真实模型")
    void delegatesToResolvedModel() {
        ChatLanguageModel delegate = mock(ChatLanguageModel.class);
        when(factory.current()).thenReturn(delegate);

        List<ChatMessage> messages = List.<ChatMessage>of(UserMessage.from("hi"));
        model.generate(messages);

        verify(delegate).generate(messages);
    }
}
