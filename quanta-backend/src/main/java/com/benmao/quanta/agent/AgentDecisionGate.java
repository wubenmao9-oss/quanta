package com.benmao.quanta.agent;

/**
 * 置信度门控（设计 §3）：{@code confidence < 阈值} → 强制降级为 {@code WAIT}，理由记为「依据不足」。
 *
 * <p>纯函数、无 IO、可单测；阈值来自 {@link AgentProperties#getConfidenceThreshold()}。
 */
public final class AgentDecisionGate {

    private AgentDecisionGate() {
    }

    /**
     * 应用置信度门控。
     *
     * @param decision  原始决策
     * @param threshold 阈值（0~1）
     * @return 门控结果（含是否降级与说明）
     */
    public static GateResult apply(AgentDecision decision, double threshold) {
        if (decision == null) {
            return new GateResult(new AgentDecision(AgentAction.WAIT, 0.0, "无决策（fail-closed）", null), true,
                    "无决策，降级 WAIT");
        }
        // WAIT/NOOP 本就不动作，无需降级
        if (decision.action() == AgentAction.WAIT || decision.action() == AgentAction.NOOP) {
            return new GateResult(decision, false, null);
        }
        if (decision.confidence() < threshold) {
            String why = "依据不足：confidence " + decision.confidence() + " < 阈值 " + threshold
                    + "（原动作 " + decision.action() + "，已强制降级 WAIT）";
            return new GateResult(decision.downToWait(why), true, why);
        }
        return new GateResult(decision, false, null);
    }

    /**
     * 门控结果。
     *
     * @param decision   门控后的决策（低置信度时 action 已被改为 WAIT）
     * @param downgraded 是否发生了降级
     * @param note       降级说明（未降级为 null）
     */
    public record GateResult(AgentDecision decision, boolean downgraded, String note) {
    }
}
