package com.benmao.quanta.agent;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.memory.MemoryService;
import com.benmao.quanta.schedule.TriggerSource;
import com.benmao.quanta.strategy.StrategyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 中枢编排（M10）：一次「研判」的完整链路。
 *
 * <p>链路（设计 §3 / §5）：
 * <ol>
 *   <li>取 ACTIVE 策略 Prompt 作系统提示词；</li>
 *   <li>构建实时快照（{@link AgentSnapshotService}）＋输出契约，作为用户消息；</li>
 *   <li>调用 {@code @AiService}（只挂只读工具）取回文本与 token 用量；</li>
 *   <li>容错解析为 {@link AgentDecision}；</li>
 *   <li><b>置信度门控</b>（{@link AgentDecisionGate}）：{@code confidence < 阈值} → 强制降级 {@code WAIT}；</li>
 *   <li>仅当门控后动作仍为写动作/PLAN 时，经 {@link AgentWriteTools} 执行——而它<b>只</b>转交
 *       {@code TradingService}（必过 {@code RiskGuard}），Agent 层无任何路径可绕过风控；</li>
 *   <li>无论成功失败，一律写 {@code agent_decision_log}（含 input_snapshot / tokens / latency / 执行结果）。</li>
 * </ol>
 *
 * <p><b>fail-closed</b>：大模型调用或解析失败时，不抛错中断，而是降级为 {@code WAIT} 并照样记日志——
 * 宁可「少做一次」，也绝不「在不确定时下单」。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 输出契约：约束模型只吐一个 JSON 对象（防幻觉、便于确定性解析）。 */
    private static final String CONTRACT = """
            请基于以上快照与你的策略，输出【唯一一个 JSON 对象】，不要输出任何解释性文字或代码围栏。
            字段：
            - action: 必须为以下之一：OPEN, CLOSE, MODIFY, PLAN, WAIT, NOOP
            - confidence: 0~1 的小数，表示你的把握程度
            - reason: 简短中文理由
            - params: 动作参数对象（WAIT/NOOP 可为 {}）
            动作语义与 params：
            - OPEN 开仓：{symbol(可空), side(BUY/SELL), volume(手数,可空), sl(止损价,必填), tp(止盈价,可空), tpMode(SOFT/REAL,可空)}
            - CLOSE 平仓：{ticket(持仓号,必填), symbol(可空)}
            - MODIFY 改仓：{ticket(必填), sl(可空), tp(可空)}
            - PLAN 计划：{planType(PRICE/PATTERN/TIME), targetPrice(PRICE 必填), invalidCond(可空), expireAt(可空,ISO 时间)}
            - WAIT 等待：不动作，等下一次唤醒（依据不足时的默认选择）
            - NOOP：什么都不做
            铁律：开仓/平仓/改仓都会由系统再做一次风控校验并可能被拒绝；不要编造 ticket，请先用工具查询真实持仓。
            若依据不足，请输出 action=WAIT 并说明原因。
            """;

    private final AgentProperties props;
    private final AgentSnapshotService snapshotService;
    private final TradingAgentAi ai;
    private final AgentDecisionParser parser;
    private final AgentWriteTools writeTools;
    private final AgentSessionService sessionService;
    private final AgentModelFactory modelFactory;
    private final AgentMarketTools marketTools;
    private final StrategyMapper strategyMapper;
    private final AgentDecisionLogMapper logMapper;
    private final MemoryService memoryService;
    private final StrategyProperties strategyProps;
    private final ObjectMapper mapper;

    public AgentService(AgentProperties props,
                        AgentSnapshotService snapshotService,
                        TradingAgentAi ai,
                        AgentDecisionParser parser,
                        AgentWriteTools writeTools,
                        AgentSessionService sessionService,
                        AgentModelFactory modelFactory,
                        AgentMarketTools marketTools,
                        StrategyMapper strategyMapper,
                        AgentDecisionLogMapper logMapper,
                        MemoryService memoryService,
                        StrategyProperties strategyProps,
                        ObjectMapper mapper) {
        this.props = props;
        this.snapshotService = snapshotService;
        this.ai = ai;
        this.parser = parser;
        this.writeTools = writeTools;
        this.sessionService = sessionService;
        this.modelFactory = modelFactory;
        this.marketTools = marketTools;
        this.strategyMapper = strategyMapper;
        this.logMapper = logMapper;
        this.memoryService = memoryService;
        this.strategyProps = strategyProps;
        this.mapper = mapper;
    }

    // ==================== 主链路 ====================

    /** 执行一次研判并落地决策日志。 */
    public AgentDecideResult decide(AgentDecideRequest req) {
        if (!props.isEnabled()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "Agent 中枢已关闭（quanta.agent.enabled=false），拒绝决策请求", 409);
        }
        String triggerType = normalizeTrigger(req == null ? null : req.triggerType());
        String symbol = firstNonBlank(req == null ? null : req.symbol(), props.getDefaultSymbol());
        String timeframe = firstNonBlank(req == null ? null : req.timeframe(), props.getDefaultTimeframe());
        Long sessionIdIn = req == null ? null : req.sessionId();
        String wakeReason = req == null ? null : req.reason();

        Strategy strategy = resolveStrategy(req == null ? null : req.strategyId());
        Long sessionId = sessionService.ensureSession(sessionIdIn, symbol, strategy.getId(), null, "MANUAL");

        Map<String, Object> snapshot = snapshotService.build(symbol, timeframe);
        String snapshotJson = toJson(snapshot);
        String traceId = traceId();

        long start = System.currentTimeMillis();
        AgentAction modelAction = null;
        AgentDecision gated;
        boolean downgraded;
        Integer tokens = null;
        String model = null;
        String llmError = null;
        try {
            String userMessage = buildUserMessage(snapshot, triggerType, wakeReason);
            Result<String> llm = ai.decide(strategy.getPrompt(), userMessage);
            model = modelFactory.currentModelName();
            tokens = totalTokens(llm);
            AgentDecision parsed = parser.parse(llm.content());
            modelAction = parsed.action();
            AgentDecisionGate.GateResult gate =
                    AgentDecisionGate.apply(parsed, props.getConfidenceThreshold());
            gated = gate.decision();
            downgraded = gate.downgraded();
        } catch (RuntimeException e) {
            llmError = msg(e);
            gated = new AgentDecision(AgentAction.WAIT, 0.0,
                    "大模型调用/解析失败（fail-closed，已降级 WAIT）：" + llmError, Map.of());
            downgraded = true;
            log.warn("Agent 研判失败，fail-closed 降级 WAIT：{}", llmError);
        }
        int latencyMs = (int) (System.currentTimeMillis() - start);

        // 唯一写出口：仅门控后仍为写动作/PLAN 时执行（低置信度已被降级为 WAIT，天然不执行）
        ExecOutcome exec = executeIfNeeded(gated, symbol, sessionId, traceId);

        String reason = exec.note().isEmpty() ? gated.reason() : gated.reason() + " | " + exec.note();

        AgentDecisionLog entity = new AgentDecisionLog();
        entity.setSessionId(sessionId);
        entity.setTraceId(traceId);
        entity.setTriggerType(triggerType);
        entity.setInputSnapshot(snapshotJson);
        entity.setModel(model);
        entity.setPromptVersion(strategy.getVersion());
        entity.setAction(gated.action().name());
        entity.setActionParams(toJson(gated.params()));
        entity.setConfidence(BigDecimal.valueOf(gated.confidence()).setScale(2, RoundingMode.HALF_UP));
        entity.setReason(reason);
        entity.setTokens(tokens);
        entity.setLatencyMs(latencyMs);
        logMapper.insert(entity);

        log.info("Agent 决策落库 id={} session={} trigger={} 原动作={} 最终动作={} confidence={} tokens={} latency={}ms 执行={} 执行错误={}",
                entity.getId(), sessionId, triggerType, modelAction, gated.action(), gated.confidence(),
                tokens, latencyMs, exec.executed(), exec.error());

        // 记忆钩子（best-effort）：会话内决策数达阈值即产出一张记忆卡；失败绝不影响本次研判结果
        try {
            memoryService.afterDecision(sessionId, symbol, strategy.getId(), null);
        } catch (RuntimeException e) {
            log.warn("记忆卡产出失败（不影响本次决策）：session={} err={}", sessionId, e.getMessage());
        }

        return new AgentDecideResult(entity.getId(), sessionId, traceId, triggerType,
                strategy.getId(), strategy.getName(), strategy.getVersion(), model,
                gated.action(), modelAction, gated.confidence(), downgraded, reason, gated.params(),
                exec.executed(), exec.error(), exec.ticket(), tokens, latencyMs, llmError);
    }

    // ==================== 观测 ====================

    /** 最近决策日志（最新在前）。 */
    public List<AgentDecisionLog> listDecisions(int limit) {
        int n = limit > 0 ? Math.min(limit, 200) : props.getDecisionListDefaultLimit();
        return logMapper.listRecent(n);
    }

    /** 契约与工具清单（验收用：只读工具可见、写工具不暴露给模型）。 */
    public Map<String, Object> describeTools() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actions", AgentAction.names());
        m.put("confidenceThreshold", props.getConfidenceThreshold());
        m.put("writeActions", List.of(AgentAction.OPEN.name(), AgentAction.CLOSE.name(), AgentAction.MODIFY.name()));
        m.put("writeToolsExposedToLlm", false);
        m.put("readTools", readToolNames());
        m.put("note", "写动作不暴露给大模型；仅当置信度门控通过后由 Java 经 TradingService→RiskGuard 确定性执行");
        return m;
    }

    private List<Map<String, Object>> readToolNames() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Method method : AgentMarketTools.class.getDeclaredMethods()) {
            dev.langchain4j.agent.tool.Tool tool = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (tool == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", tool.name().isBlank() ? method.getName() : tool.name());
            m.put("description", tool.value());
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing(a -> String.valueOf(a.get("name"))));
        return out;
    }

    // ==================== 写执行 ====================

    /** 依据门控后的动作执行写操作；非写动作/PLAN 直接跳过。 */
    private ExecOutcome executeIfNeeded(AgentDecision d, String symbol, Long sessionId, String traceId) {
        AgentAction action = d.action();
        if (!action.isWrite() && action != AgentAction.PLAN) {
            return ExecOutcome.skipped();
        }
        Map<String, Object> p = d.params();
        try {
            switch (action) {
                case OPEN -> {
                    String sym = firstNonBlank(str(p.get("symbol")), symbol);
                    BigDecimal volume = dec(p.get("volume"), BigDecimal.valueOf(props.getDefaultVolume()));
                    TradeOrder o = writeTools.open(sym, str(p.get("side")), volume,
                            dec(p.get("sl"), null), dec(p.get("tp"), null), str(p.get("tpMode")),
                            sessionId, traceId);
                    return ExecOutcome.ok(o.getTicket(), "执行成功：开仓 ticket=" + o.getTicket()
                            + " status=" + o.getStatus());
                }
                case CLOSE -> {
                    Long ticket = lng(p.get("ticket"));
                    TradeOrder o = writeTools.close(firstNonBlank(str(p.get("symbol")), symbol),
                            ticket, sessionId, traceId);
                    return ExecOutcome.ok(ticket, "执行成功：平仓 ticket=" + ticket + " status=" + o.getStatus());
                }
                case MODIFY -> {
                    Long ticket = lng(p.get("ticket"));
                    TradeOrder o = writeTools.modify(firstNonBlank(str(p.get("symbol")), symbol), ticket,
                            dec(p.get("sl"), null), dec(p.get("tp"), null), sessionId, traceId);
                    return ExecOutcome.ok(ticket, "执行成功：改单 ticket=" + ticket + " status=" + o.getStatus());
                }
                case PLAN -> {
                    TradePlan plan = writeTools.plan(sessionId, str(p.get("planType")),
                            dec(p.get("targetPrice"), null), str(p.get("invalidCond")), ldt(p.get("expireAt")));
                    return ExecOutcome.ok(null, "执行成功：登记计划 id=" + plan.getId() + " 状态=" + plan.getStatus());
                }
                default -> {
                    return ExecOutcome.skipped();
                }
            }
        } catch (RuntimeException e) {
            // 风控拒绝 / 券商拒绝 / 参数非法：如实记录，绝不静默
            return ExecOutcome.failed("执行未生效：" + msg(e), msg(e));
        }
    }

    // ==================== 内部实现 ====================

    private Strategy resolveStrategy(Long strategyId) {
        if (strategyId != null) {
            Strategy s = strategyMapper.getById(strategyId);
            if (s == null) {
                throw new BizException(ResultCode.NOT_FOUND.code(), "策略不存在: id=" + strategyId, 404);
            }
            if (!"ACTIVE".equalsIgnoreCase(s.getStatus()) && !"DRILL".equalsIgnoreCase(s.getStatus())) {
                throw new BizException(ResultCode.BIZ_ERROR.code(),
                        "策略未上线（status=" + s.getStatus() + "），拒绝用于研判；"
                                + "请先经门禁通过并人工批准置 ACTIVE，或在演练模式内置 DRILL", 400);
            }
            requirePrompt(s);
            return s;
        }
        List<Strategy> all = strategyMapper.listAll();
        // 演练模式：优先选用 DRILL 策略（绕过门禁、仅用于验证 AI 能否真实下单）
        if (strategyProps.getGate().isDrillEnabled()) {
            Strategy drill = all.stream()
                    .filter(s -> "DRILL".equalsIgnoreCase(s.getStatus()))
                    .findFirst().orElse(null);
            if (drill != null) {
                requirePrompt(drill);
                log.warn("演练模式已开启：本次研判使用 DRILL 策略 id={} name={}（未通过门禁，禁止用于实盘资金）",
                        drill.getId(), drill.getName());
                return drill;
            }
        }
        Strategy active = all.stream().filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus())).findFirst().orElse(null);
        if (active == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "没有 ACTIVE 策略，无法研判；请先使策略通过门禁并批准置 ACTIVE", 400);
        }
        requirePrompt(active);
        return active;
    }

    private static void requirePrompt(Strategy s) {
        if (s.getPrompt() == null || s.getPrompt().isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "策略 " + s.getId() + "（" + s.getName() + "）缺少 Prompt，无法作为系统提示词", 400);
        }
    }

    private String buildUserMessage(Map<String, Object> snapshot, String triggerType, String wakeReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("触发源：").append(triggerType).append('\n');
        if (wakeReason != null && !wakeReason.isBlank()) {
            sb.append("唤醒原因：").append(wakeReason.trim()).append('\n');
        }
        sb.append("实时快照（JSON）：\n").append(toJson(snapshot)).append("\n\n").append(CONTRACT);
        return sb.toString();
    }

    private String normalizeTrigger(String raw) {
        if (raw == null || raw.isBlank()) {
            return TriggerSource.T6.name();
        }
        return TriggerSource.parse(raw).name();
    }

    private static Integer totalTokens(Result<String> r) {
        if (r == null || r.tokenUsage() == null) {
            return null;
        }
        return r.tokenUsage().totalTokenCount();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("JSON 序列化失败：{}", e.getMessage());
            return "{}";
        }
    }

    private static String traceId() {
        String t = MDC.get(com.benmao.quanta.common.Result.TRACE_ID_KEY);
        if (t != null && !t.isBlank()) {
            return t;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Object v, BigDecimal dft) {
        if (v == null) {
            return dft;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return dft;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static Long lng(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime ldt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime t) {
            return t;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String msg(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 写执行结果。
     *
     * @param executed 是否真正下达了写操作
     * @param ticket   产生的持仓号（OPEN/CLOSE/MODIFY 为相关 ticket，可空）
     * @param note     追加到日志 reason 的可读说明
     * @param error    执行失败原因（可空）
     */
    private record ExecOutcome(boolean executed, Long ticket, String note, String error) {

        static ExecOutcome skipped() {
            return new ExecOutcome(false, null, "", null);
        }

        static ExecOutcome ok(Long ticket, String note) {
            return new ExecOutcome(true, ticket, note, null);
        }

        static ExecOutcome failed(String note, String error) {
            return new ExecOutcome(false, null, note, error);
        }
    }
}
