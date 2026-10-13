package com.benmao.quanta.trade;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.ops.ManualTradeService;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskSide;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 交易执行接口（M6）：开仓 / 平仓 / 改 SL-TP。
 *
 * <p>三个接口都只是 {@link ManualTradeService} 的薄封装（M13 起本层为<b>人工</b>入口）——
 * <b>所有写操作都必须穿过风控</b>，因此本层不提供任何"绕过风控直接下单"的入口；
 * 人工路径额外落 {@code operation_audit} 并通知关联会话（设计 §8.3）。
 * AI 路径走 {@code AgentWriteTools} → {@code TradingService}。
 */
@RestController
@RequestMapping("/api/trade")
public class TradeController {

    private final ManualTradeService manualTradeService;

    public TradeController(ManualTradeService manualTradeService) {
        this.manualTradeService = manualTradeService;
    }

    /** 市价开仓（风控拒绝 → 400 且回执合法边界）。 */
    @PostMapping("/open")
    public Result<TradeOrder> open(@RequestBody OpenReq req) {
        TradeCommand cmd = new TradeCommand(RiskAction.OPEN, req.symbol(), RiskSide.parse(req.side()),
                req.volume(), req.price(), req.sl(), req.tp(), req.tpMode(),
                null, req.sessionId(), req.planId(), req.traceId());
        return Result.ok(manualTradeService.open(cmd));
    }

    /** 平仓（按 ticket）。 */
    @PostMapping("/close")
    public Result<TradeOrder> close(@RequestBody CloseReq req) {
        TradeCommand cmd = new TradeCommand(RiskAction.CLOSE, req.symbol(), null,
                null, null, null, null, null, req.ticket(), req.sessionId(), null, req.traceId());
        return Result.ok(manualTradeService.close(cmd));
    }

    /** 修改 SL/TP（传 null 的字段保持原值）。 */
    @PostMapping("/modify")
    public Result<TradeOrder> modify(@RequestBody ModifyReq req) {
        TradeCommand cmd = new TradeCommand(RiskAction.MODIFY, req.symbol(), null,
                null, req.price(), req.sl(), req.tp(), null, req.ticket(), req.sessionId(), null, req.traceId());
        return Result.ok(manualTradeService.modify(cmd));
    }

    /** 开仓请求体；side 支持 BUY/SELL（不区分大小写，也兼容 0/1、long/short）。 */
    public record OpenReq(String symbol,
                          String side,
                          BigDecimal volume,
                          BigDecimal price,
                          BigDecimal sl,
                          BigDecimal tp,
                          String tpMode,
                          Long sessionId,
                          Long planId,
                          String traceId) {
    }

    /** 平仓请求体。 */
    public record CloseReq(String symbol,
                           Long ticket,
                           Long sessionId,
                           String traceId) {
    }

    /** 改单请求体。 */
    public record ModifyReq(String symbol,
                            Long ticket,
                            BigDecimal price,
                            BigDecimal sl,
                            BigDecimal tp,
                            Long sessionId,
                            String traceId) {
    }
}
