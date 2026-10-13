package com.benmao.quanta.budget;

import java.util.List;

/**
 * 一次降级流程的执行结果（设计 §8.4）。
 *
 * @param triggered        本次是否触发降级流程（未超限为 false）
 * @param newlyDegraded    是否本次「新进入」降级（用于去重邮件告警）
 * @param alreadyDegraded  调用前是否已处于降级态（幂等/可重入标记）
 * @param closedTickets    本次平掉的盈利仓 ticket
 * @param keptTickets      保留的仓位（profit ≤ 0）ticket
 * @param protectedTickets 本次新补挂 HARD SL 的仓位 ticket
 * @param failures         处置过程中的失败说明（best-effort，逐条记录）
 * @param notified         是否发送了告警邮件
 * @param note             可读说明
 */
public record DegradeOutcome(boolean triggered,
                             boolean newlyDegraded,
                             boolean alreadyDegraded,
                             List<Long> closedTickets,
                             List<Long> keptTickets,
                             List<Long> protectedTickets,
                             List<String> failures,
                             boolean notified,
                             String note) {

    /** 未触发（未超限）。 */
    public static DegradeOutcome notTriggered(String note) {
        return new DegradeOutcome(false, false, false, List.of(), List.of(), List.of(), List.of(), false, note);
    }
}
