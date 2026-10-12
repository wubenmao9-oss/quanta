package com.benmao.quanta.risk;

import java.math.BigDecimal;

/**
 * 风控校验请求（三类写操作合一；按动作取用相应字段）。
 *
 * @param action    动作：OPEN / CLOSE / MODIFY
 * @param symbol    品种（逻辑名或券商名，如 GOLD / XAUUSD）；CLOSE/MODIFY 可空（从持仓反查）
 * @param side      方向（OPEN 必填；MODIFY/CLOSE 可空，从持仓反查）
 * @param volume    手数（OPEN 必填）
 * @param price     参考价（开仓价 / 改单基准价）；空则用实时市价（BUY 取 ask、SELL 取 bid）
 * @param sl        止损价（OPEN 必填；MODIFY 为新的 SL）
 * @param tp        止盈价（可选）
 * @param tpMode    止盈模式（HARD_TP / SOFT_TP / NONE，仅记录，不影响风控判定）
 * @param ticket    持仓单号（CLOSE/MODIFY 必填）
 * @param sessionId 发起会话（R13 持仓互斥用；可空 = 人工）
 * @param traceId   追踪号（R8 幂等用）
 */
public record RiskRequest(RiskAction action,
                          String symbol,
                          RiskSide side,
                          BigDecimal volume,
                          BigDecimal price,
                          BigDecimal sl,
                          BigDecimal tp,
                          String tpMode,
                          Long ticket,
                          Long sessionId,
                          String traceId) {
}
