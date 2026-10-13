package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradePlanMapper;
import com.benmao.quanta.memory.MemoryService;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 编排单测（M10 全链路）：快照→LLM→解析→门控→执行→落日志。
 *
 * <p>覆盖：低置信度不执行、非法动作/调用失败 fail-closed、写动作经 TradingService、风控拒绝被如实记录、日志字段齐全。
 */
class AgentServiceTest {

    private final ObjectMapper om = new ObjectMapper();

    private AgentProperties props;
    private AgentSnapshotService snapshot;
    private TradingAgentAi ai;
    private TradingService tradingService;
    private TradePlanMapper planMapper;
    private AgentSessionService sessionService;
    private AgentModelFactory modelFactory;
    private AgentMarketTools marketTools;
    private StrategyMapper strategyMapper;
    private AgentDecisionLogMapper logMapper;
    private MemoryService memoryService;
    private AgentService service;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        snapshot = mock(AgentSnapshotService.class);
        ai = mock(TradingAgentAi.class);
        tradingService = mock(TradingService.class);
        planMapper = mock(TradePlanMapper.class);
        sessionService = mock(AgentSessionService.class);
        modelFactory = mock(AgentModelFactory.class);
        marketTools = mock(AgentMarketTools.class);
        strategyMapper = mock(StrategyMapper.class);
        logMapper = mock(AgentDecisionLogMapper.class);
        memoryService = mock(MemoryService.class);

        AgentWriteTools writeTools = new AgentWriteTools(tradingService, planMapper);
        service = new AgentService(props, snapshot, ai, new AgentDecisionParser(om), writeTools,
                sessionService, modelFactory, marketTools, strategyMapper, logMapper, memoryService, om);

        when(sessionService.ensureSession(any(), any(), any(), any(), any())).thenReturn(7L);
        when(snapshot.build(any(), any())).thenReturn(new LinkedHashMap<>(Map.of("symbol", "GOLD")));
        when(modelFactory.currentModelName()).thenReturn("deepseek-chat");
        when(strategyMapper.listAll()).thenReturn(List.of(activeStrategy()));
        when(logMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, AgentDecisionLog.class).setId(999L);
            return 1;
        });
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("低置信度 OPEN → 降级 WAIT、不执行、日志落 WAIT（验收②）")
    void lowConfidenceOpenDowngradedAndNotExecuted() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"OPEN\",\"confidence\":0.3,"
                + "\"reason\":\"信号弱\",\"params\":{\"side\":\"BUY\",\"sl\":4200.00}}"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.WAIT, r.action());
        assertEquals(AgentAction.OPEN, r.modelAction());
        assertTrue(r.downgraded());
        assertFalse(r.executed());
        assertEquals(0.3, r.confidence(), 1e-9);
        assertEquals(Integer.valueOf(30), r.tokens());
        assertNotNull(r.latencyMs());
        assertNull(r.llmError());
        verify(tradingService, never()).openPosition(any());

        AgentDecisionLog log = capturedLog();
        assertEquals("WAIT", log.getAction());
        assertEquals(7L, log.getSessionId());
        assertEquals("T6", log.getTriggerType());
        assertEquals(30, log.getTokens());
        assertNotNull(log.getLatencyMs());
        assertNotNull(log.getInputSnapshot());
        assertEquals(1, log.getPromptVersion());
        assertEquals("deepseek-chat", log.getModel());
        assertEquals(0, new BigDecimal("0.30").compareTo(log.getConfidence()));
        assertTrue(log.getReason().contains("依据不足"));
    }

    @Test
    @DisplayName("高置信度 OPEN → 经 TradingService 开仓并记「执行成功」")
    void highConfidenceOpenExecutesThroughTradingService() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"OPEN\",\"confidence\":0.9,"
                + "\"reason\":\"金叉\",\"params\":{\"side\":\"BUY\",\"sl\":4200.00,\"tp\":4230.00}}"));
        when(tradingService.openPosition(any())).thenReturn(order(7001L, "FILLED"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.OPEN, r.action());
        assertFalse(r.downgraded());
        assertTrue(r.executed());
        assertEquals(Long.valueOf(7001L), r.ticket());

        ArgumentCaptor<TradeCommand> c = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).openPosition(c.capture());
        assertEquals("OPEN", c.getValue().action().name());
        assertEquals(7L, c.getValue().sessionId());
        assertTrue(capturedLog().getReason().contains("执行成功"));
    }

    @Test
    @DisplayName("风控拒绝 → executed=false、原因入库，日志 action 仍为 OPEN（验收④）")
    void riskRejectionRecordedButNotExecuted() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"OPEN\",\"confidence\":0.95,"
                + "\"reason\":\"突破\",\"params\":{\"side\":\"BUY\"}}"));
        when(tradingService.openPosition(any()))
                .thenThrow(new BizException("风控拒绝[R5]：开仓必须设置止损（SL 不能为空或 0）"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.OPEN, r.action());
        assertFalse(r.executed());
        assertNotNull(r.executionError());
        assertTrue(r.executionError().contains("风控拒绝"));

        AgentDecisionLog log = capturedLog();
        assertEquals("OPEN", log.getAction());
        assertTrue(log.getReason().contains("执行未生效"));
    }

    @Test
    @DisplayName("非法动作（幻觉）→ fail-closed 降级 WAIT 并照样落日志")
    void illegalModelActionFailsClosed() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"LIQUIDATE_ALL\",\"confidence\":0.99}"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.WAIT, r.action());
        assertNull(r.modelAction());
        assertNotNull(r.llmError());
        assertTrue(r.downgraded());
        assertFalse(r.executed());
        verify(tradingService, never()).openPosition(any());
        assertEquals("WAIT", capturedLog().getAction());
    }

    @Test
    @DisplayName("大模型调用失败 → fail-closed 降级 WAIT 并照样落日志")
    void llmFailureFailsClosed() {
        when(ai.decide(any(), any()))
                .thenThrow(new BizException("没有激活的 LLM 配置，无法调用大模型"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.WAIT, r.action());
        assertTrue(r.llmError().contains("LLM"));
        assertFalse(r.executed());
        assertNotNull(r.decisionLogId());
        assertTrue(capturedLog().getReason().contains("fail-closed"));
    }

    @Test
    @DisplayName("WAIT → 不触发任何写操作，且不视为降级")
    void waitDoesNotExecute() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"WAIT\",\"confidence\":0.8,\"reason\":\"观察\"}"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.WAIT, r.action());
        assertFalse(r.downgraded());
        assertFalse(r.executed());
        verifyNoInteractions(tradingService);
    }

    @Test
    @DisplayName("中枢关闭 → 直接拒绝（409）")
    void disabledAgentRejects() {
        props.setEnabled(false);
        assertThrows(BizException.class, () -> service.decide(req()));
    }

    @Test
    @DisplayName("指定未上线（DRAFT）策略 → 拒绝研判")
    void nonActiveStrategyRejected() {
        Strategy draft = activeStrategy();
        draft.setStatus("DRAFT");
        when(strategyMapper.getById(99L)).thenReturn(draft);

        BizException e = assertThrows(BizException.class,
                () -> service.decide(new AgentDecideRequest("GOLD", "M5", 99L, null, null, null)));
        assertTrue(e.getMessage().contains("未上线"));
    }

    @Test
    @DisplayName("契约与工具清单：写工具不暴露给模型，只读工具 6 个")
    void describeToolsShowsContract() {
        Map<String, Object> d = service.describeTools();

        assertEquals(Boolean.FALSE, d.get("writeToolsExposedToLlm"));
        assertTrue(String.valueOf(d.get("actions")).contains("NOOP"));
        assertEquals(0.6, (Double) d.get("confidenceThreshold"), 1e-9);
        assertEquals(6, ((List<?>) d.get("readTools")).size());
    }

    @Test
    @DisplayName("决策落库后调用记忆钩子（best-effort，携带会话/品种/策略）")
    void memoryHookInvokedAfterDecision() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"WAIT\",\"confidence\":0.8,\"reason\":\"观察\"}"));

        service.decide(req());

        verify(memoryService).afterDecision(7L, "GOLD", 13L, null);
    }

    @Test
    @DisplayName("记忆钩子异常不影响决策结果（best-effort 吞异常）")
    void memoryHookFailureIsSwallowed() {
        when(ai.decide(any(), any())).thenReturn(llm("{\"action\":\"WAIT\",\"confidence\":0.8,\"reason\":\"观察\"}"));
        when(memoryService.afterDecision(any(), any(), any(), any()))
                .thenThrow(new BizException("记忆层故障"));

        AgentDecideResult r = service.decide(req());

        assertEquals(AgentAction.WAIT, r.action());
    }

    // ==================== 辅助 ====================

    private static AgentDecideRequest req() {
        return new AgentDecideRequest("GOLD", "M5", null, null, null, "单测");
    }

    private static Result<String> llm(String content) {
        return new Result<>(content, new TokenUsage(10, 20, 30), null, null, null);
    }

    private static Strategy activeStrategy() {
        Strategy s = new Strategy();
        s.setId(13L);
        s.setName("趋势-EMA_ADX");
        s.setVersion(1);
        s.setStatus("ACTIVE");
        s.setPrompt("你是趋势策略，只在有明确趋势时开仓，必须设置止损。");
        return s;
    }

    private static TradeOrder order(long ticket, String status) {
        TradeOrder o = new TradeOrder();
        o.setTicket(ticket);
        o.setStatus(status);
        return o;
    }

    private AgentDecisionLog capturedLog() {
        ArgumentCaptor<AgentDecisionLog> c = ArgumentCaptor.forClass(AgentDecisionLog.class);
        verify(logMapper).insert(c.capture());
        return c.getValue();
    }
}
