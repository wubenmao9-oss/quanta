package com.benmao.quanta.agent;

import com.benmao.quanta.entity.AgentMessage;
import com.benmao.quanta.mapper.AgentMessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自由问答服务（{@code POST /api/agent/chat}）：非流式「一次问答 + 工具调用轨迹」。
 *
 * <p>与交易研判（{@link AgentService}）完全隔离：本服务<b>只调用只读工具</b>
 * （{@link AgentMarketTools} 行情/账户/指标 + {@link QuantaChatTools} 回测/策略库/流水/日志），
 * <b>不</b>触碰任何写路径，因此无需风控参与，也不改变 id=42 等正在运行的策略行为。
 *
 * <p>工具循环由本类手工驱动（而非 {@code @AiService}），目的是把每一次工具调用的
 * 名称 / 入参 / 结果 / 耗时完整暴露给前端，满足「看得见 AI 调了什么工具」的诉求。
 *
 * <p>多轮上下文：按 {@code sessionId} 从 {@code agent_message} 表读取最近若干轮
 * （含客户端可选的 {@code history} 兜底），回答后把本轮 user/assistant 落库。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** 单次问答最多允许的工具往返轮数（防止模型空转烧 token）。 */
    private static final int MAX_TOOL_ROUNDS = 6;

    /** 拼接历史时最多回带的轮数。 */
    private static final int MAX_HISTORY_TURNS = 20;

    /** 单条历史消息截断长度。 */
    private static final int MAX_CONTENT_CHARS = 4000;

    private static final String PERSONA_GENERAL = """
            你是 quanta 交易系统的 AI 助手，正在与交易员对话。
            你可以调用只读工具获取真实数据：账户资金、实时报价、当前持仓、K 线、指标、回测、
            策略库、历史成交流水、最近 AI 决策日志。
            要求：
            - 回答必须基于工具返回的真实数据；需要数据就调用工具，不要凭记忆编造。
            - 工具返回 {"error":...} 时如实说明该数据取不到，不要假装成功。
            - 任何下单/平仓/改单都只能给出建议，不能代执行；请提示用户在界面手动确认。
            - 用简体中文，简洁、条理清晰；数据用表格或短列表呈现。
            """;

    private static final String PERSONA_STRATEGY = """
            你是 quanta 交易系统的「策略设计助手」，任务是帮用户把一个交易想法打磨成
            一条可入库的策略 Prompt（策略库中的 prompt 字段）。
            你可调用的工具：
            - availableBacktestStrategies：查看可被自动回测的内置规则策略清单（key + 中文名）
            - runBacktest：对某个内置规则策略跑单档回测，返回真实胜率/盈亏比/最大回撤/期望
            - scanBacktestTiers：多档点差敏感性扫描，判断策略是否靠低成本吃饭
            - strategyCatalog：查看库内已有策略，避免重复
            重要限制（必须如实告知用户，禁止编造回测数据）：
            - 本系统的自动回测目前只能跑「内置规则策略」（用 key 指定），
              无法直接回测一段自然语言 Prompt；
            - 因此若用户想验证某条已有规则，就调用回测工具并把真实数据复述出来；
              若要新建策略，你负责产出结构清晰的中文策略 Prompt。
            新策略 Prompt 应包含：适用行情(TREND/RANGE)、入场条件、出场条件(固定 SL/TP)、
            过滤条件、交易时段、风险约束（单笔风险比、最大回撤容忍）。
            铁律：新策略需经「回测门禁 + 人工批准」后才能上线，你产出的只是草稿。
            最后，请用一行给出建议的策略名，格式：策略名：<名称>
            """;

    private final AgentModelFactory modelFactory;
    private final AgentMarketTools marketTools;
    private final QuantaChatTools chatTools;
    private final AgentSessionService sessionService;
    private final AgentMessageMapper messageMapper;
    private final ObjectMapper mapper;

    /** 工具名 → 绑定（bean + 方法 + 参数名）。 */
    private final Map<String, ToolBinding> bindings = new LinkedHashMap<>();
    /** 暴露给模型的工具规格（与 bindings 一一对应）。 */
    private final List<ToolSpecification> specs = new ArrayList<>();

    public AgentChatService(AgentModelFactory modelFactory,
                            AgentMarketTools marketTools,
                            QuantaChatTools chatTools,
                            AgentSessionService sessionService,
                            AgentMessageMapper messageMapper,
                            ObjectMapper mapper) {
        this.modelFactory = modelFactory;
        this.marketTools = marketTools;
        this.chatTools = chatTools;
        this.sessionService = sessionService;
        this.messageMapper = messageMapper;
        this.mapper = mapper;
        register(marketTools);
        register(chatTools);
        log.info("AI 自由问答工具已装载 {} 个：{}", bindings.size(), bindings.keySet());
    }

    // ==================== 主入口 ====================

    /** 一次自由问答。 */
    public AgentChatResult chat(AgentChatRequest req) {
        String message = req == null ? null : req.message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String symbol = firstNonBlank(req.symbol(), "GOLD");
        String timeframe = firstNonBlank(req.timeframe(), "M5");
        String persona = normalizePersona(req.persona());

        Long sessionId = sessionService.ensureSession(req.sessionId(), symbol, null, null, "MANUAL");
        saveMessage(sessionId, "user", message, null);

        List<ChatMessage> history = loadHistory(sessionId, req.history());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt(persona, symbol, timeframe, req.context())));
        messages.addAll(history);
        messages.add(UserMessage.from(message));

        long start = System.currentTimeMillis();
        String model = null;
        String reply = null;
        String error = null;
        Integer tokens = null;
        List<AgentChatResult.ToolCall> traces = new ArrayList<>();
        try {
            ChatLanguageModel llm = modelFactory.current();
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                ChatResponse resp = llm.chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(specs)
                        .build());
                model = modelFactory.currentModelName();
                tokens = sumTokens(tokens, resp.tokenUsage());
                AiMessage ai = resp.aiMessage();
                if (ai == null) {
                    reply = "(模型未返回内容)";
                    break;
                }
                if (ai.hasToolExecutionRequests()) {
                    messages.add(ai);
                    for (ToolExecutionRequest tr : ai.toolExecutionRequests()) {
                        AgentChatResult.ToolCall call = execute(tr);
                        traces.add(call);
                        Object payload = call.ok() ? call.result() : Map.of("error", String.valueOf(call.error()));
                        messages.add(ToolExecutionResultMessage.from(tr, toJson(payload)));
                    }
                    continue;
                }
                reply = ai.text();
                break;
            }
            if (reply == null) {
                reply = "（已达到单次问答的工具调用上限，未能给出最终答复；请把问题拆小一点再问一次）";
            }
        } catch (RuntimeException e) {
            error = msg(e);
            reply = "抱歉，本次调用大模型失败：" + error
                    + "\n请检查配置层是否已保存并激活一条 LLM 配置（OPENAI_COMPAT）。";
            log.warn("自由问答失败：session={} err={}", sessionId, error);
        }
        long latency = System.currentTimeMillis() - start;

        saveMessage(sessionId, "assistant", reply, tokens);
        saveToolTrace(sessionId, traces);
        log.info("自由问答完成：session={} persona={} rounds={} tools={} tokens={} latency={}ms",
                sessionId, persona, traces.size(), traces.size(), tokens, latency);

        return new AgentChatResult(sessionId, reply, model, tokens, latency, error, traces);
    }

    // ==================== 工具注册与执行 ====================

    private void register(Object bean) {
        List<ToolSpecification> beanSpecs = ToolSpecifications.toolSpecificationsFrom(bean);
        Map<String, Method> byName = new LinkedHashMap<>();
        for (Method m : bean.getClass().getMethods()) {
            Tool t = m.getAnnotation(Tool.class);
            if (t == null) {
                continue;
            }
            String name = t.name() == null || t.name().isBlank() ? m.getName() : t.name();
            byName.putIfAbsent(name, m);
        }
        for (ToolSpecification spec : beanSpecs) {
            Method m = byName.get(spec.name());
            if (m == null) {
                continue;
            }
            bindings.put(spec.name(), new ToolBinding(bean, m, paramNames(m), javaParamNames(m)));
            specs.add(spec);
        }
    }

    private static List<String> paramNames(Method m) {
        List<String> names = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            P ann = p.getAnnotation(P.class);
            names.add(ann != null && !ann.value().isBlank() ? ann.value() : p.getName());
        }
        return names;
    }

    /** Java 形参名（含 {@code @P} 被误写成中文名称的历史工具，作为兜底键）。 */
    private static List<String> javaParamNames(Method m) {
        List<String> names = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            names.add(p.getName());
        }
        return names;
    }

    private AgentChatResult.ToolCall execute(ToolExecutionRequest tr) {
        long t0 = System.currentTimeMillis();
        String name = tr.name();
        String args = tr.arguments();
        try {
            ToolBinding b = bindings.get(name);
            if (b == null) {
                throw new IllegalArgumentException("未知工具：" + name + "，可用：" + bindings.keySet());
            }
            Object result = invoke(b, args);
            return new AgentChatResult.ToolCall(name, args, result, true,
                    System.currentTimeMillis() - t0, null);
        } catch (RuntimeException e) {
            log.warn("工具调用失败：tool={} args={} err={}", name, args, msg(e));
            return new AgentChatResult.ToolCall(name, args, null, false,
                    System.currentTimeMillis() - t0, msg(e));
        }
    }

    private Object invoke(ToolBinding b, String argsJson) {
        Map<String, Object> args = parseArgs(argsJson);
        Parameter[] ps = b.method.getParameters();
        Object[] argv = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            Object raw = args.get(b.paramNames.get(i));
            if (raw == null && b.fallbackNames.get(i) != null) {
                raw = args.get(b.fallbackNames.get(i));
            }
            argv[i] = raw == null ? null
                    : mapper.convertValue(raw, mapper.getTypeFactory().constructType(ps[i].getParameterizedType()));
        }
        try {
            b.method.setAccessible(true);
            return b.method.invoke(b.bean, argv);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause == null ? e.getMessage() : msg(cause), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("工具反射调用失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("工具入参不是合法 JSON 对象：" + json);
        }
    }

    // ==================== 上下文与落库 ====================

    private String systemPrompt(String persona, String symbol, String timeframe, String context) {
        String base = "STRATEGY".equals(persona) ? PERSONA_STRATEGY : PERSONA_GENERAL;
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n当前上下文：品种=").append(symbol).append("，周期=").append(timeframe).append('\n');
        if (context != null && !context.isBlank()) {
            sb.append("用户提供的额外背景（可能是策略草稿，供你参考）：\n").append(context.trim()).append('\n');
        }
        return sb.toString();
    }

    private List<ChatMessage> loadHistory(Long sessionId, List<AgentChatRequest.ChatTurn> fallback) {
        List<ChatMessage> out = new ArrayList<>();
        List<AgentMessage> rows = messageMapper.listRecentDialogue(sessionId, MAX_HISTORY_TURNS);
        if (rows != null && !rows.isEmpty()) {
            List<AgentMessage> ordered = new ArrayList<>(rows);
            Collections.reverse(ordered);
            for (AgentMessage m : ordered) {
                out.add(toMessage(m.getRole(), m.getContent()));
            }
            return out;
        }
        if (fallback != null) {
            for (AgentChatRequest.ChatTurn t : fallback) {
                out.add(toMessage(t.role(), t.content()));
            }
        }
        return out;
    }

    private static ChatMessage toMessage(String role, String content) {
        String text = truncate(content);
        if (text == null) {
            return UserMessage.from("");
        }
        return "assistant".equalsIgnoreCase(role) ? AiMessage.from(text) : UserMessage.from(text);
    }

    private void saveMessage(Long sessionId, String role, String content, Integer tokens) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            AgentMessage m = new AgentMessage();
            m.setSessionId(sessionId);
            m.setRole(role);
            m.setContent(truncate(content));
            m.setTokens(tokens);
            messageMapper.insert(m);
        } catch (RuntimeException e) {
            log.warn("会话消息落库失败（不影响问答）：session={} role={} err={}", sessionId, role, e.getMessage());
        }
    }

    private void saveToolTrace(Long sessionId, List<AgentChatResult.ToolCall> traces) {
        if (traces.isEmpty()) {
            return;
        }
        saveMessage(sessionId, "tool", toJson(traces), null);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS);
    }

    private static Integer sumTokens(Integer acc, TokenUsage usage) {
        if (usage == null || usage.totalTokenCount() == null) {
            return acc;
        }
        return (acc == null ? 0 : acc) + usage.totalTokenCount();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String normalizePersona(String p) {
        if (p == null || p.isBlank()) {
            return "GENERAL";
        }
        String v = p.trim().toUpperCase(java.util.Locale.ROOT);
        return "STRATEGY".equals(v) ? "STRATEGY" : "GENERAL";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String msg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 工具绑定：bean + 方法 + 主参数名（@P）+ 兜底参数名（Java 形参名）。 */
    private record ToolBinding(Object bean, Method method, List<String> paramNames, List<String> fallbackNames) {
    }
}
