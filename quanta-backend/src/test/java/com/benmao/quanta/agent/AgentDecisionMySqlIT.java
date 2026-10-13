package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.AgentSessionMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10 Agent 决策真库集成测试：真实落 {@code agent_decision_log} / {@code agent_session}，并验证写路径必过真库 RiskGuard。
 *
 * <p>确定性保障：用 {@link TestPropertySource} 把 AES 密钥换成本 IT 专属值，
 * 使库内任何「激活的 LLM 配置」都无法解密 → {@code AgentService} 走 <b>fail-closed 降级 WAIT</b>，
 * 从而不依赖外网大模型、也不依赖库内是否恰好存在可用的 LLM 配置。
 *
 * <p>验证点：
 * <ol>
 *   <li>每次决策写库：session_id / trigger_type / input_snapshot / tokens / latency / confidence / action_params 齐备；</li>
 *   <li>大模型不可用 → fail-closed 降级 WAIT 并照样落库；</li>
 *   <li><b>写路径必经风控</b>：写工具对「缺 SL 的开仓」必被真库 RiskGuard 拒绝，绝不下单（验收④）。</li>
 * </ol>
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=AgentDecisionMySqlIT "-Dsurefire.failIfNoSpecifiedTests=true"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@TestPropertySource(properties = "quanta.security.aes-key=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=")
class AgentDecisionMySqlIT {

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentWriteTools agentWriteTools;

    @Autowired
    private AgentDecisionLogMapper logMapper;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private StrategyMapper strategyMapper;

    private final List<Long> createdLogIds = new ArrayList<>();
    private final List<Long> createdSessionIds = new ArrayList<>();

    /** 记录本次产生的日志/会话 id，供 {@link #cleanup()} 精确清理，避免污染真库。 */
    private void track(AgentDecideResult r) {
        if (r.decisionLogId() != null) {
            createdLogIds.add(r.decisionLogId());
        }
        if (r.sessionId() != null) {
            createdSessionIds.add(r.sessionId());
        }
    }

    @BeforeEach
    void precondition() {
        boolean hasActive = strategyMapper.listAll().stream()
                .anyMatch(s -> "ACTIVE".equalsIgnoreCase(s.getStatus())
                        && s.getPrompt() != null && !s.getPrompt().isBlank());
        assertTrue(hasActive, "前置：需存在含 Prompt 的 ACTIVE 策略（M9 已批准 id 13/14）");
    }

    @AfterEach
    void cleanup() {
        createdLogIds.forEach(logMapper::deleteById);
        createdSessionIds.forEach(sessionMapper::deleteById);
        createdLogIds.clear();
        createdSessionIds.clear();
    }

    @Test
    @DisplayName("每次决策写库：session/trigger/snapshot/latency/confidence/action_params 齐备")
    void writesDecisionLogOnEveryDecide() {
        AgentDecideResult r = agentService.decide(new AgentDecideRequest("GOLD", "M5", null, null, "T6", "IT 人工唤醒"));
        track(r);

        // 本 IT 的 LLM 不可用 → fail-closed 降级 WAIT（不依赖外网）
        assertNotNull(r.llmError(), "应记录大模型调用/解析失败原因");
        assertEquals(AgentAction.WAIT, r.action());
        assertTrue(r.downgraded());
        assertFalse(r.executed());
        assertNotNull(r.latencyMs());
        assertNotNull(r.decisionLogId());

        AgentDecisionLog log = logMapper.getById(r.decisionLogId());
        assertNotNull(log, "决策日志应已落库");
        assertNotNull(log.getSessionId(), "session_id 必填");
        assertEquals("T6", log.getTriggerType());
        assertEquals("WAIT", log.getAction());
        assertNotNull(log.getLatencyMs());
        assertNotNull(log.getConfidence());
        assertNotNull(log.getActionParams());
        assertNotNull(log.getInputSnapshot());
        assertTrue(log.getInputSnapshot().startsWith("{"), "input_snapshot 应为 JSON 对象");
    }

    @Test
    @DisplayName("按会话复用：带 sessionId 再决策，日志归入同一会话")
    void reusesSessionWhenProvided() {
        AgentDecideResult first = agentService.decide(new AgentDecideRequest("GOLD", "M5", null, null, "T6", "IT-1"));
        track(first);

        AgentDecideResult second = agentService.decide(
                new AgentDecideRequest("GOLD", "M5", null, first.sessionId(), "T2", "IT-2"));
        track(second);

        assertEquals(first.sessionId(), second.sessionId(), "应复用同一会话");
        assertEquals("T2", logMapper.getById(second.decisionLogId()).getTriggerType());
    }

    @Test
    @DisplayName("写路径必经真库 RiskGuard：缺 SL 的开仓被拒、不下单（验收④）")
    void writePathGoesThroughRealRiskGuard() {
        // 无 SL 的开仓 → 真库 RiskGuard（R5）或行情不可用时的保守拒绝，二者都含「风控」且都不下单
        BizException e = assertThrows(BizException.class, () -> agentWriteTools.open(
                "GOLD", "BUY", new BigDecimal("0.01"), null, null, null, null, "it-trace"));
        assertTrue(e.getMessage().contains("风控"), "写工具必须穿过风控并回执风控原因：" + e.getMessage());
    }
}
