package com.benmao.quanta.budget;

import java.time.LocalDate;

/**
 * Token 预算状态快照（设计 §8.4）。
 *
 * @param tokenDate  用量归属日期
 * @param usedTokens 当日已用 Token（按 {@code agent_decision_log.tokens} 按日聚合）
 * @param budget     当日预算；{@code null} = 无上限（LLM 配置未设 tokenBudgetDaily）
 * @param exceeded   是否超限（{@code budget} 非空且 {@code usedTokens > budget}）
 * @param degraded   当前是否处于降级态（sys_state.degraded）
 * @param usedRatio  已用占预算比例（无上限时为 {@code null}）
 * @param remaining  剩余额度（无上限时为 {@code null}；超限不小于 0）
 * @param note       可读说明
 */
public record BudgetStatus(LocalDate tokenDate,
                           long usedTokens,
                           Long budget,
                           boolean exceeded,
                           boolean degraded,
                           Double usedRatio,
                           Long remaining,
                           String note) {
}
