package com.benmao.quanta.trade;

import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskOrigin;
import com.benmao.quanta.risk.RiskSide;

import java.math.BigDecimal;

/**
 * 交易执行命令（OPEN/CLOSE/MODIFY 三类写操作合一；按动作取用相应字段）。
 *
 * <p>与 {@code RiskRequest} 的区别：多带一个 {@code planId}（开仓来源的交易计划，落 trade_order.plan_id）。
 *
 * @param action    动作：OPEN / CLOSE / MODIFY
 * @param symbol    品种（逻辑名或券商名，如 GOLD / XAUUSD）；CLOSE/MODIFY 可空（从持仓/流水反查）
 * @param side      方向（OPEN 必填）
 * @param volume    手数（OPEN 必填）
 * @param price     参考价（OPEN 可空 = 用实时市价；MODIFY 为改单基准价）
 * @param sl        止损价（OPEN 必填；MODIFY 为新 SL）
 * @param tp        止盈价（可选）
 * @param tpMode    止盈模式（HARD / SOFT / NONE，仅记录）
 * @param ticket    持仓单号（CLOSE/MODIFY 必填）
 * @param sessionId 发起会话（R13 持仓互斥用；可空 = 人工）
 * @param planId    交易计划 id（可空）
 * @param traceId   追踪号（R8 幂等用；空则由 Java 生成）
 * @param origin    发起来源（M13：AI 自主 / MANUAL 人工；仅影响 R11 三态判定）
 */
public record TradeCommand(RiskAction action,
                           String symbol,
                           RiskSide side,
                           BigDecimal volume,
                           BigDecimal price,
                           BigDecimal sl,
                           BigDecimal tp,
                           String tpMode,
                           Long ticket,
                           Long sessionId,
                           Long planId,
                           String traceId,
                           RiskOrigin origin) {

    /** 兼容构造：默认 {@link RiskOrigin#AI}。 */
    public TradeCommand(RiskAction action, String symbol, RiskSide side, BigDecimal volume, BigDecimal price,
                        BigDecimal sl, BigDecimal tp, String tpMode, Long ticket, Long sessionId, Long planId,
                        String traceId) {
        this(action, symbol, side, volume, price, sl, tp, tpMode, ticket, sessionId, planId, traceId, RiskOrigin.AI);
    }

    /** 复制命令并替换来源（人工路径置 {@link RiskOrigin#MANUAL}）。 */
    public TradeCommand withOrigin(RiskOrigin newOrigin) {
        return new TradeCommand(action, symbol, side, volume, price, sl, tp, tpMode,
                ticket, sessionId, planId, traceId, newOrigin);
    }
}
