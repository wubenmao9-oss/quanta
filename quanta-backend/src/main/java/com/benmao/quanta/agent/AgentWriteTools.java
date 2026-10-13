package com.benmao.quanta.agent;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.TradePlanMapper;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Agent 的<b>唯一写出口</b>（设计 §3 / §5）。
 *
 * <p>铁律：所有写操作<b>只</b>能由此类发起，且一律转交 {@link TradingService}——而 {@code TradingService}
 * 的每个方法都<b>先穿过 {@code RiskGuard}</b>（风控拒绝即抛，绝不触达券商、绝不落库）。
 * 因此 Agent 层<b>没有任何路径可绕过风控</b>：本类不直接调用桥接层的写方法，也不提供任何开关。
 *
 * <p>与只读工具（{@link AgentMarketTools}）不同，本类<b>不</b>作为 {@code @Tool} 暴露给大模型：
 * 写动作来自「契约 + 置信度门控通过」的确定性编排（{@code AgentService}），
 * 以避免「模型既调工具又返回契约」导致的重复下单，并保证低置信度时绝不执行。
 */
@Component
public class AgentWriteTools {

    private final TradingService tradingService;
    private final TradePlanMapper tradePlanMapper;

    public AgentWriteTools(TradingService tradingService, TradePlanMapper tradePlanMapper) {
        this.tradingService = tradingService;
        this.tradePlanMapper = tradePlanMapper;
    }

    /** 开仓：经 TradingService → RiskGuard。 */
    public TradeOrder open(String symbol, String side, BigDecimal volume, BigDecimal sl, BigDecimal tp,
                           String tpMode, Long sessionId, String traceId) {
        TradeCommand cmd = new TradeCommand(RiskAction.OPEN, symbol, RiskSide.parse(side), volume, null,
                sl, tp, tpMode, null, sessionId, null, traceId);
        return tradingService.openPosition(cmd);
    }

    /** 平仓：经 TradingService → RiskGuard。 */
    public TradeOrder close(String symbol, Long ticket, Long sessionId, String traceId) {
        TradeCommand cmd = new TradeCommand(RiskAction.CLOSE, symbol, null, null, null, null, null, null,
                ticket, sessionId, null, traceId);
        return tradingService.closePosition(cmd);
    }

    /** 改 SL/TP：经 TradingService → RiskGuard（R13/R14/R15）。 */
    public TradeOrder modify(String symbol, Long ticket, BigDecimal sl, BigDecimal tp, Long sessionId, String traceId) {
        TradeCommand cmd = new TradeCommand(RiskAction.MODIFY, symbol, null, null, null, sl, tp, null,
                ticket, sessionId, null, traceId);
        return tradingService.modifySlTp(cmd);
    }

    /**
     * 登记交易计划（设计 §3 的 {@code PLAN}）：交 Java 盯盘，不触发任何下单，故无需风控。
     *
     * @param sessionId   归属会话
     * @param planType    PRICE / PATTERN / TIME
     * @param targetPrice 预期价（TIME 型可空）
     * @param invalidCond 失效条件（可空）
     * @param expireAt    失效时间（可空=不过期）
     */
    public TradePlan plan(Long sessionId, String planType, BigDecimal targetPrice, String invalidCond, LocalDateTime expireAt) {
        String type = (planType == null || planType.isBlank()) ? "PRICE" : planType.trim().toUpperCase(Locale.ROOT);
        if (!"PRICE".equals(type) && !"PATTERN".equals(type) && !"TIME".equals(type)) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "planType 非法：" + planType + "，可选 PRICE, PATTERN, TIME", 400);
        }
        if ("PRICE".equals(type) && (targetPrice == null || targetPrice.signum() == 0)) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "PRICE 型计划必须提供非零 targetPrice", 400);
        }
        TradePlan p = new TradePlan();
        p.setSessionId(sessionId);
        p.setPlanType(type);
        p.setTargetPrice(targetPrice);
        p.setInvalidCond(invalidCond);
        p.setExpireAt(expireAt);
        p.setStatus("PENDING");
        tradePlanMapper.insert(p);
        return p;
    }
}
