package com.benmao.quanta.schedule;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 一次触发命中：由 TriggerEngine 判定后交给 SessionManager 分配窗口。
 *
 * @param source     触发源
 * @param symbol     品种
 * @param reason     命中原因（可读）
 * @param planId     关联的计划 id（T1 用，可为 null）
 * @param ticket     关联的持仓 ticket（T5 用，可为 null；非空时参与持仓级互斥）
 * @param barTime    命中时的 K 线时间（可为 null）
 * @param detectedAt 命中时刻
 */
public record TriggerHit(
        TriggerSource source,
        String symbol,
        String reason,
        Long planId,
        Long ticket,
        LocalDateTime barTime,
        Instant detectedAt
) {
}
