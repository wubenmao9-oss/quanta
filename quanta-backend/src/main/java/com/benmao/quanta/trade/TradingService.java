package com.benmao.quanta.trade;

import com.benmao.mt5.Mt5ErrorCode;
import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskGuard;
import com.benmao.quanta.risk.RiskRequest;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.review.TradeClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 交易执行与事务闭环（M6）。
 *
 * <p>铁律：<b>本类是唯一能把"写操作"下达给券商的入口</b>，且每一次都<b>先穿过 RiskGuard</b>——
 * 风控拒绝则直接抛业务异常，绝不触达券商、绝不落库。这在结构上保证"AI 无任何路径可绕过风控"。
 *
 * <p>事务语义（"下单成功 ⇔ 流水入库"）：
 * <ul>
 *   <li>成功成交 → 同事务写入 trade_order（开仓 status=FILLED；平仓状态改 CLOSED）；</li>
 *   <li>风控拒绝 / 券商拒绝 / 桥接异常 → 抛异常、事务回滚，<b>不留任何孤儿流水</b>；</li>
 *   <li>失败单不落库，故 R8 幂等窗口只对"真实成交过"的单生效，允许失败后原样重试。</li>
 * </ul>
 *
 * <p><b>已知局限（如实记录）</b>：券商调用是不可回滚的远程副作用。若下单已成交、但随后的落库失败，
 * 本地事务会回滚 → "有真实持仓、无本地流水"。此时以券商持仓为准，需人工对账（M7 持仓巡检兜底）。
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final Mt5BridgeService bridge;
    private final RiskGuard riskGuard;
    private final TradeOrderMapper tradeOrderMapper;
    private final ApplicationEventPublisher eventPublisher;

    public TradingService(Mt5BridgeService bridge,
                          RiskGuard riskGuard,
                          TradeOrderMapper tradeOrderMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.bridge = bridge;
        this.riskGuard = riskGuard;
        this.tradeOrderMapper = tradeOrderMapper;
        this.eventPublisher = eventPublisher;
    }

    // ==================== 开仓 ====================

    /** 市价开仓：先过风控 → 券商下单 → 同事务写流水（status=FILLED）。 */
    @Transactional
    public TradeOrder openPosition(TradeCommand cmd) {
        RiskSide side = cmd.side();
        String symbol = requireText(cmd.symbol(), "品种");
        BigDecimal volume = cmd.volume();

        String traceId = traceIdOf(cmd);
        // 唯一入口：风控拒绝即抛，不触达券商、不落库
        riskGuard.evaluate(toRiskRequest(cmd, traceId)).orThrow();

        // 开仓前持仓快照：用于定位本次新开的 ticket（净持仓账户的 ticket 可能与订单号不同）
        Set<Long> before = ticketsOf(bridge.positions(symbol));

        OrderResult r = bridge.openMarket(symbol, side == RiskSide.BUY,
                toDouble(volume), cmd.sl() == null ? 0.0 : toDouble(cmd.sl()),
                cmd.tp() == null ? 0.0 : toDouble(cmd.tp()));
        ensureSuccess("开仓", r);

        Long ticket = findNewTicket(symbol, before, r);
        String brokerSymbol = resolveSafe(symbol);

        TradeOrder e = new TradeOrder();
        e.setPlanId(cmd.planId());
        e.setSessionId(cmd.sessionId());
        e.setTraceId(traceId);
        e.setTicket(ticket);
        e.setSymbol(brokerSymbol);
        e.setCmd(side.name());
        e.setVolume(volume);
        e.setPrice(toBigDecimal(r.price()));
        e.setSl(cmd.sl());
        e.setTp(cmd.tp());
        e.setTpMode(tpModeOf(cmd));
        e.setTrailMode("NONE");
        e.setStatus("FILLED");
        e.setRetcode(r.retcode());
        e.setOpenTime(LocalDateTime.now());
        tradeOrderMapper.insert(e);

        log.info("开仓成交并落库 trace={} ticket={} symbol={} cmd={} volume={} price={} sl={} tp={} id={}",
                traceId, ticket, brokerSymbol, side, volume, e.getPrice(), e.getSl(), e.getTp(), e.getId());
        return e;
    }

    // ==================== 平仓 ====================

    /** 平仓：先过风控 → 券商平仓 → 同事务把流水改 CLOSED（并落 profit/swap/close_price/close_time）。 */
    @Transactional
    public TradeOrder closePosition(TradeCommand cmd) {
        Long ticket = requireTicket(cmd.ticket());
        String traceId = traceIdOf(cmd);
        riskGuard.evaluate(toRiskRequest(cmd, traceId)).orThrow();

        TradeOrder order = tradeOrderMapper.findLatestByTicket(ticket);
        if (order == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "本地无 ticket=" + ticket + " 的流水，拒绝平仓（失败即保守）", 400);
        }
        // 平仓前取持仓快照：swap 只能在持仓期间读到（平掉后就没了）
        Position pos = findPosition(bridge.positions(null), ticket);

        String symbol = cmd.symbol() != null && !cmd.symbol().isBlank() ? cmd.symbol() : order.getSymbol();
        OrderResult r = bridge.closePosition(symbol, ticket);
        ensureSuccess("平仓", r);

        order.setStatus("CLOSED");
        order.setRetcode(r.retcode());
        order.setProfit(toBigDecimal(r.profit() != null ? r.profit() : (pos == null ? null : pos.profit())));
        order.setSwap(pos == null ? order.getSwap() : toBigDecimal(pos.swap()));
        order.setClosePrice(toBigDecimal(r.price()));
        order.setCloseTime(LocalDateTime.now());
        tradeOrderMapper.update(order);

        log.info("平仓成交并落库 trace={} ticket={} profit={} swap={} closePrice={}",
                traceId, ticket, order.getProfit(), order.getSwap(), order.getClosePrice());
        // 平仓事务提交后触发复盘（AFTER_COMMIT 监听生成单笔报告；报告失败不回滚本次成交）
        eventPublisher.publishEvent(new TradeClosedEvent(order));
        return order;
    }

    // ==================== 改仓（SL/TP） ====================

    /** 改 SL/TP：先过风控（R13/R14/R15）→ 券商改单 → 同事务更新流水。 */
    @Transactional
    public TradeOrder modifySlTp(TradeCommand cmd) {
        Long ticket = requireTicket(cmd.ticket());
        String traceId = traceIdOf(cmd);
        riskGuard.evaluate(toRiskRequest(cmd, traceId)).orThrow();

        TradeOrder order = tradeOrderMapper.findLatestByTicket(ticket);
        if (order == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "本地无 ticket=" + ticket + " 的流水，拒绝改单（失败即保守）", 400);
        }
        String symbol = cmd.symbol() != null && !cmd.symbol().isBlank() ? cmd.symbol() : order.getSymbol();
        OrderResult r = bridge.modifySlTp(symbol, ticket,
                cmd.sl() == null ? null : toDouble(cmd.sl()),
                cmd.tp() == null ? null : toDouble(cmd.tp()));
        ensureSuccess("修改 SL/TP", r);

        if (cmd.sl() != null) {
            order.setSl(cmd.sl());
        }
        if (cmd.tp() != null) {
            order.setTp(cmd.tp());
        }
        order.setRetcode(r.retcode());
        tradeOrderMapper.update(order);

        log.info("改单成交并落库 trace={} ticket={} sl={} tp={}", traceId, ticket, order.getSl(), order.getTp());
        return order;
    }

    // ==================== 内部实现 ====================

    private RiskRequest toRiskRequest(TradeCommand cmd, String traceId) {
        return new RiskRequest(cmd.action(), cmd.symbol(), cmd.side(), cmd.volume(), cmd.price(),
                cmd.sl(), cmd.tp(), cmd.tpMode(), cmd.ticket(), cmd.sessionId(), traceId);
    }

    /** 券商拒绝（success=false）→ 归一化为可读原因并抛出；上层事务随之回滚，不留流水。 */
    private static void ensureSuccess(String action, OrderResult r) {
        if (r == null) {
            throw new BizException(ResultCode.BIZ_ERROR.code(), action + "失败：桥接服务无返回", 502);
        }
        if (!r.success()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    action + "被券商拒绝（retcode=" + r.retcode() + "）：" + Mt5ErrorCode.explain(r.retcode())
                            + (r.error() == null || r.error().isBlank() ? "" : " | " + r.error()), 400);
        }
    }

    /** 定位本次新开的持仓 ticket；净持仓账户下订单号可能与持仓号不同，取不到则退回订单号。 */
    private Long findNewTicket(String symbol, Set<Long> before, OrderResult r) {
        for (Position p : bridge.positions(symbol)) {
            if (!before.contains(p.ticket())) {
                return p.ticket();
            }
        }
        return r.order();
    }

    private static Set<Long> ticketsOf(List<Position> positions) {
        Set<Long> set = new HashSet<>();
        for (Position p : positions) {
            set.add(p.ticket());
        }
        return set;
    }

    private static Position findPosition(List<Position> positions, long ticket) {
        for (Position p : positions) {
            if (p.ticket() == ticket) {
                return p;
            }
        }
        return null;
    }

    private String resolveSafe(String symbol) {
        try {
            return bridge.resolveSymbol(symbol);
        } catch (RuntimeException e) {
            return symbol;
        }
    }

    private static String tpModeOf(TradeCommand cmd) {
        if (cmd.tpMode() != null && !cmd.tpMode().isBlank()) {
            return cmd.tpMode();
        }
        return cmd.tp() != null ? "SOFT" : "NONE";
    }

    private static String traceIdOf(TradeCommand cmd) {
        if (cmd.traceId() != null && !cmd.traceId().isBlank()) {
            return cmd.traceId();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), name + "不能为空", 400);
        }
        return value;
    }

    private static Long requireTicket(Long ticket) {
        if (ticket == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "ticket 不能为空", 400);
        }
        return ticket;
    }

    private static double toDouble(BigDecimal v) {
        return v.doubleValue();
    }

    private static BigDecimal toBigDecimal(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }
}
