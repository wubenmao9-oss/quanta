package com.benmao.quanta.cycle;

import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskOrigin;
import com.benmao.quanta.risk.RiskProfile;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * cycle 执行器的<b>唯一写出口</b>：所有下单/平仓都构造 {@link TradeCommand} 并经
 * {@link TradingService}（→ {@code RiskGuard}，{@link RiskProfile#GRID} 档）。
 *
 * <p>与 {@code AgentWriteTools} 同级；<b>不</b>暴露给大模型（执行器不接受 LLM 指令）。
 * 采用 {@link RiskOrigin#AI} 以便 {@code RunMode=PAUSED} 时被 R11 自动拦截。
 */
@Component
public class CycleWriteTools {

    private final TradingService tradingService;

    public CycleWriteTools(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    /** 开一腿（市价单，带真实 SL，TP 留空由执行器按整轮止盈价判定）。 */
    public TradeOrder openLeg(String symbol, RiskSide side, double volume, double sl,
                              Long sessionId, String traceId) {
        TradeCommand cmd = new TradeCommand(RiskAction.OPEN, symbol, side, bd(volume), null,
                bd(sl), null, "NONE", null, sessionId, null, traceId, RiskOrigin.AI, RiskProfile.GRID);
        return tradingService.openPosition(cmd);
    }

    /** 平一腿（按票号）。 */
    public TradeOrder closeLeg(String symbol, long ticket, Long sessionId, String traceId) {
        TradeCommand cmd = new TradeCommand(RiskAction.CLOSE, symbol, null, null, null,
                null, null, null, ticket, sessionId, null, traceId, RiskOrigin.AI, RiskProfile.GRID);
        return tradingService.closePosition(cmd);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(5, RoundingMode.HALF_UP);
    }
}
