package com.benmao.quanta.risk;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.List;

/**
 * 风控结论。
 *
 * <p>{@code allowed=true} 表示该动作通过全部规则；否则 {@code violations} 列出每一条被拒原因
 * （结构化 + 合法边界值），供 AI 修正重试。
 *
 * @param action    被校验的动作
 * @param allowed   是否放行
 * @param violations 违规明细（放行时为空）
 * @param checked   本次实际跑过的规则编号（便于回溯哪些规则生效）
 */
public record RiskDecision(RiskAction action,
                           boolean allowed,
                           List<RiskViolation> violations,
                           List<String> checked) {

    public RiskDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
        checked = checked == null ? List.of() : List.copyOf(checked);
    }

    /**
     * 被拒时抛业务异常（首个违规作为消息），用于「必须放行」的调用点（M6 TradingService 前置门）。
     */
    public RiskDecision orThrow() {
        if (!allowed) {
            RiskViolation first = violations.get(0);
            StringBuilder sb = new StringBuilder("风控拒绝[").append(first.rule()).append("]：")
                    .append(first.message());
            if (!first.bounds().isEmpty()) {
                sb.append("；合法边界 ").append(first.bounds());
            }
            if (violations.size() > 1) {
                sb.append("（共 ").append(violations.size()).append(" 条不通过，详见回执）");
            }
            throw new BizException(ResultCode.BIZ_ERROR.code(), sb.toString(), 400);
        }
        return this;
    }
}
