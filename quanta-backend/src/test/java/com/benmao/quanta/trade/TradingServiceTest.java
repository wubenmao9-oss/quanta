package com.benmao.quanta.trade;

import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskDecision;
import com.benmao.quanta.risk.RiskGuard;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.risk.RiskViolation;
import com.benmao.quanta.review.TradeClosedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 交易执行单测（M6）。
 *
 * <p>核心断言：<b>风控拒绝 / 券商拒绝时，既不下单也不落库</b>（无孤儿流水），
 * 成交时才同事务写入流水（开仓 FILLED / 平仓 CLOSED + swap/retcode 落库）。
 */
class TradingServiceTest {

    private Mt5BridgeService bridge;
    private RiskGuard riskGuard;
    private TradeOrderMapper mapper;
    private ApplicationEventPublisher eventPublisher;
    private TradingService service;

    @BeforeEach
    void setUp() {
        bridge = mock(Mt5BridgeService.class);
        riskGuard = mock(RiskGuard.class);
        mapper = mock(TradeOrderMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new TradingService(bridge, riskGuard, mapper, eventPublisher);
    }

    // ==================== 开仓 ====================

    @Test
    @DisplayName("开仓成交：落库 FILLED + 回填 ticket/retcode，且全字段正确")
    void openSuccessWritesFilledRow() {
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.OPEN));
        when(bridge.positions("GOLD")).thenReturn(List.of(), List.of(position(7001L, "buy", 4200.0, 0.0)));
        when(bridge.resolveSymbol("GOLD")).thenReturn("GOLD");
        when(bridge.openMarket(eq("GOLD"), eq(true), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(ok(10009, 7001L, 4200.10, null, null));

        TradeOrder saved = service.openPosition(open(RiskSide.BUY, new BigDecimal("0.01"),
                new BigDecimal("4195.10"), new BigDecimal("4210.10"), "trace-open-1"));

        assertNotNull(saved);
        assertEquals("FILLED", saved.getStatus());
        assertEquals(7001L, saved.getTicket());
        assertEquals("GOLD", saved.getSymbol());
        assertEquals("BUY", saved.getCmd());
        assertEquals(10009, saved.getRetcode());
        assertEquals("trace-open-1", saved.getTraceId());
        assertEquals(0, new BigDecimal("4200.10").compareTo(saved.getPrice()));
        assertNotNull(saved.getOpenTime());

        ArgumentCaptor<TradeOrder> captor = ArgumentCaptor.forClass(TradeOrder.class);
        verify(mapper).insert(captor.capture());
        assertEquals("FILLED", captor.getValue().getStatus());
        assertEquals(7001L, captor.getValue().getTicket());
    }

    @Test
    @DisplayName("风控拒绝：抛异常，且不触达券商、不落库（AI 无法绕过 RiskGuard）")
    void openRejectedByRiskNeverTouchesBrokerOrDb() {
        when(riskGuard.evaluate(any())).thenReturn(denied(RiskAction.OPEN, "R5", "SL_REQUIRED", "开仓必须设置止损"));

        BizException ex = assertThrows(BizException.class, () -> service.openPosition(
                open(RiskSide.BUY, new BigDecimal("0.01"), null, null, "trace-reject-1")));

        assertTrue(ex.getMessage().contains("R5"), ex.getMessage());
        verifyNoInteractions(bridge);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("券商拒绝（retcode=10016）：异常原因可读，且不落库")
    void openRejectedByBrokerWritesNoRow() {
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.OPEN));
        when(bridge.positions("GOLD")).thenReturn(List.of(), List.of());
        when(bridge.openMarket(anyString(), eq(true), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new OrderResult(false, 10016, null, null, null, null, "invalid stops", "bad stops", null, null));

        BizException ex = assertThrows(BizException.class, () -> service.openPosition(
                open(RiskSide.BUY, new BigDecimal("0.01"), new BigDecimal("4199.00"), null, "trace-bad-1")));

        assertTrue(ex.getMessage().contains("止损/止盈价格无效"), ex.getMessage());
        assertTrue(ex.getMessage().contains("10016"), ex.getMessage());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("开仓缺少品种：参数错误，直接拒绝（不触达券商）")
    void openWithoutSymbolRejected() {
        assertThrows(BizException.class, () -> service.openPosition(
                new TradeCommand(RiskAction.OPEN, "  ", RiskSide.BUY, new BigDecimal("0.01"),
                        null, new BigDecimal("4195.00"), null, null, null, null, null, "t")));
        verifyNoInteractions(bridge);
    }

    // ==================== 平仓 ====================

    @Test
    @DisplayName("平仓成交：流水改 CLOSED，profit/swap/close_price/close_time 全部落库")
    void closeUpdatesRowWithProfitAndSwap() {
        TradeOrder existing = filledOrder(900L, 7001L);
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.CLOSE));
        when(mapper.findLatestByTicket(7001L)).thenReturn(existing);
        when(bridge.positions(null)).thenReturn(List.of(position(7001L, "buy", 4200.0, -1.25)));
        when(bridge.closePosition("GOLD", 7001L))
                .thenReturn(new OrderResult(true, 10009, 7001L, 88001L, 0.01, 4205.60, "done", null, 7001L, 15.50));

        TradeOrder done = service.closePosition(close(7001L, "trace-close-1"));

        assertEquals("CLOSED", done.getStatus());
        assertEquals(10009, done.getRetcode());
        assertEquals(0, new BigDecimal("15.50").compareTo(done.getProfit()));
        assertEquals(0, new BigDecimal("-1.25").compareTo(done.getSwap()));
        assertEquals(0, new BigDecimal("4205.60").compareTo(done.getClosePrice()));
        assertNotNull(done.getCloseTime());
        verify(mapper).update(existing);
    }

    @Test
    @DisplayName("平仓成交后发布 TradeClosedEvent（供复盘 AFTER_COMMIT 监听）")
    void closePublishesTradeClosedEvent() {
        TradeOrder existing = filledOrder(900L, 7001L);
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.CLOSE));
        when(mapper.findLatestByTicket(7001L)).thenReturn(existing);
        when(bridge.positions(null)).thenReturn(List.of(position(7001L, "buy", 4200.0, -1.25)));
        when(bridge.closePosition("GOLD", 7001L))
                .thenReturn(new OrderResult(true, 10009, 7001L, 88001L, 0.01, 4205.60, "done", null, 7001L, 15.50));

        TradeOrder done = service.closePosition(close(7001L, "trace-close-1"));

        ArgumentCaptor<TradeClosedEvent> c = ArgumentCaptor.forClass(TradeClosedEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        assertEquals(done.getId(), c.getValue().order().getId());
        assertEquals("CLOSED", c.getValue().order().getStatus());
    }

    @Test
    @DisplayName("平仓：本地无该 ticket 流水 → 拒绝，不下单")
    void closeWithoutLocalRowRejected() {
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.CLOSE));
        when(mapper.findLatestByTicket(anyLong())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.closePosition(close(8888L, "trace-x")));

        assertTrue(ex.getMessage().contains("本地无 ticket=8888"), ex.getMessage());
        verify(bridge, never()).closePosition(anyString(), anyLong());
    }

    // ==================== 改仓 ====================

    @Test
    @DisplayName("改 SL/TP 成交：流水 SL/TP 同事务更新（只改 SL 时 TP 不动）")
    void modifyUpdatesSlTp() {
        TradeOrder existing = filledOrder(901L, 7002L);
        existing.setTp(new BigDecimal("4210.10"));
        when(riskGuard.evaluate(any())).thenReturn(allowed(RiskAction.MODIFY));
        when(mapper.findLatestByTicket(7002L)).thenReturn(existing);
        when(bridge.modifySlTp(eq("GOLD"), eq(7002L), eq(4198.10), isNull()))
                .thenReturn(new OrderResult(true, 10009, 7002L, null, null, null, "done", null, null, null));

        TradeOrder done = service.modifySlTp(new TradeCommand(RiskAction.MODIFY, "GOLD", null,
                null, null, new BigDecimal("4198.10"), null, null, 7002L, null, null, "trace-mod-1"));

        assertEquals(0, new BigDecimal("4198.10").compareTo(done.getSl()));
        assertEquals(0, new BigDecimal("4210.10").compareTo(done.getTp()), "只改 SL 时 TP 必须保留");
        verify(mapper).update(existing);
    }

    // ==================== 辅助 ====================

    private static RiskDecision allowed(RiskAction action) {
        return new RiskDecision(action, true, List.of(), List.of("R1", "R5"));
    }

    private static RiskDecision denied(RiskAction action, String rule, String code, String message) {
        return new RiskDecision(action, false, List.of(RiskViolation.of(rule, code, message)), List.of(rule));
    }

    private static OrderResult ok(Integer retcode, Long order, Double price, Double profit, Long closedTicket) {
        return new OrderResult(true, retcode, order, 1L, 0.01, price, "done", null, closedTicket, profit);
    }

    private static TradeCommand open(RiskSide side, BigDecimal volume, BigDecimal sl, BigDecimal tp, String traceId) {
        return new TradeCommand(RiskAction.OPEN, "GOLD", side, volume, null, sl, tp, null, null, null, null, traceId);
    }

    private static TradeCommand close(long ticket, String traceId) {
        return new TradeCommand(RiskAction.CLOSE, "GOLD", null, null, null, null, null, null, ticket, null, null, traceId);
    }

    private static TradeOrder filledOrder(Long id, Long ticket) {
        TradeOrder e = new TradeOrder();
        e.setId(id);
        e.setTicket(ticket);
        e.setSymbol("GOLD");
        e.setCmd("BUY");
        e.setVolume(new BigDecimal("0.01"));
        e.setSl(new BigDecimal("4195.10"));
        e.setStatus("FILLED");
        e.setRetcode(10009);
        e.setOpenTime(LocalDateTime.now().minusMinutes(5));
        return e;
    }

    private static Position position(long ticket, String type, double openPrice, double swap) {
        return new Position(ticket, "GOLD", type, 0.01, openPrice, openPrice, 4195.10, 4210.10, 15.50, swap, 0L, "t", null);
    }
}
