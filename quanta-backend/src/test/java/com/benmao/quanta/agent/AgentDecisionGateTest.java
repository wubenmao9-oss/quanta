package com.benmao.quanta.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 置信度门控单测（M10 验收②：confidence < 0.6 强制降级 WAIT）。 */
class AgentDecisionGateTest {

    private static AgentDecision decision(AgentAction action, double conf) {
        return new AgentDecision(action, conf, "原始理由", Map.of("side", "BUY"));
    }

    @Test
    @DisplayName("高置信度写动作：放行，不降级")
    void highConfidencePasses() {
        AgentDecisionGate.GateResult r = AgentDecisionGate.apply(decision(AgentAction.OPEN, 0.9), 0.6);
        assertFalse(r.downgraded());
        assertEquals(AgentAction.OPEN, r.decision().action());
    }

    @Test
    @DisplayName("边界：confidence == 阈值 → 放行（不小于即通过）")
    void boundaryEqualToThresholdPasses() {
        AgentDecisionGate.GateResult r = AgentDecisionGate.apply(decision(AgentAction.OPEN, 0.6), 0.6);
        assertFalse(r.downgraded());
        assertEquals(AgentAction.OPEN, r.decision().action());
    }

    @Test
    @DisplayName("低置信度写动作：强制降级 WAIT，理由记为「依据不足」且保留原参数")
    void lowConfidenceDowngradesToWait() {
        AgentDecisionGate.GateResult r = AgentDecisionGate.apply(decision(AgentAction.OPEN, 0.59), 0.6);
        assertTrue(r.downgraded());
        assertEquals(AgentAction.WAIT, r.decision().action());
        assertTrue(r.decision().reason().contains("依据不足"));
        assertEquals("BUY", r.decision().params().get("side"));
        assertEquals(0.59, r.decision().confidence(), 1e-9);
    }

    @Test
    @DisplayName("低置信度 PLAN 同样被降级 WAIT")
    void lowConfidencePlanDowngraded() {
        AgentDecisionGate.GateResult r = AgentDecisionGate.apply(decision(AgentAction.PLAN, 0.3), 0.6);
        assertTrue(r.downgraded());
        assertEquals(AgentAction.WAIT, r.decision().action());
    }

    @Test
    @DisplayName("WAIT / NOOP 本就不动作，不降级")
    void waitAndNoopNotDowngraded() {
        assertFalse(AgentDecisionGate.apply(decision(AgentAction.WAIT, 0.1), 0.6).downgraded());
        assertFalse(AgentDecisionGate.apply(decision(AgentAction.NOOP, 0.0), 0.6).downgraded());
    }

    @Test
    @DisplayName("空决策 → fail-closed 降级 WAIT")
    void nullDecisionFailsClosed() {
        AgentDecisionGate.GateResult r = AgentDecisionGate.apply(null, 0.6);
        assertTrue(r.downgraded());
        assertEquals(AgentAction.WAIT, r.decision().action());
    }
}
