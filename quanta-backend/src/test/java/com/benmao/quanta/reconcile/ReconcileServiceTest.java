package com.benmao.quanta.reconcile;

import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.ops.AlertService;
import com.benmao.quanta.ops.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账与断线恢复单测（M13）：启动认领建单 + 对账差异报告 + <b>只报告不改账</b>。
 *
 * <p>固定时钟 2026-10-13 08:00 UTC；桥接/流水/审计/告警全部 mock，行为可逐条断言。
 */
class ReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Mt5BridgeService bridge;
    private TradeOrderMapper tradeOrderMapper;
    private OperationAuditService auditService;
    private AlertService alertService;
    private ReconcileService service;

    @BeforeEach
    void setUp() {
        bridge = mock(Mt5BridgeService.class);
        tradeOrderMapper = mock(TradeOrderMapper.class);
        auditService = mock(OperationAuditService.class);
        alertService = mock(AlertService.class);
        service = new ReconcileService(bridge, tradeOrderMapper, auditService, alertService,
                new ReconcileProperties(), CLOCK);
    }

    // ==================== 启动认领 ====================

    @Test
    @DisplayName("认领：MT5 有持仓而本地无流水 → 补建 status=FILLED/trace=CLAIM-<ticket> 的认领单 + 审计 + 告警")
    void claimBuildsOrderAuditAndAlert() {
        when(bridge.positions(isNull())).thenReturn(List.of(position(7001L)));
        when(tradeOrderMapper.findLatestByTicket(7001L)).thenReturn(null);

        List<Long> claimed = service.claimExistingPositions();

        assertEquals(List.of(7001L), claimed);
        ArgumentCaptor<TradeOrder> cap = ArgumentCaptor.forClass(TradeOrder.class);
        verify(tradeOrderMapper).insert(cap.capture());
        TradeOrder e = cap.getValue();
        assertEquals("CLAIM-7001", e.getTraceId());
        assertEquals(7001L, e.getTicket());
        assertEquals("GOLD", e.getSymbol());
        assertEquals("BUY", e.getCmd());
        assertEquals("FILLED", e.getStatus());
        assertEquals("NONE", e.getTpMode());
        assertEquals("NONE", e.getTrailMode());
        assertEquals(0, new BigDecimal("0.01").compareTo(e.getVolume()));
        assertNull(e.getSl(), "SL=0 应记为 null");
        assertNull(e.getTp(), "TP=0 应记为 null");

        verify(auditService).write(eq("RECONCILE"), eq("CLAIM"), eq("7001"), isNull(),
                anyString(), eq("CLAIM-7001"));
        verify(alertService).orphanClaimed(anyString());
    }

    @Test
    @DisplayName("认领：本地已有流水不重复建单；无持仓则直接返回空")
    void claimSkipsExistingAndEmpty() {
        when(bridge.positions(isNull())).thenReturn(List.of(position(7001L)));
        when(tradeOrderMapper.findLatestByTicket(7001L)).thenReturn(new TradeOrder());

        assertTrue(service.claimExistingPositions().isEmpty());
        verify(tradeOrderMapper, never()).insert(any());
        verify(alertService, never()).orphanClaimed(anyString());
    }

    // ==================== 对账（只报告不改账） ====================

    @Test
    @DisplayName("对账：盈亏不符 → 报 PROFIT_MISMATCH，落审 RECONCILE_DIFF + 告警，绝不改账")
    void reconcileReportsProfitMismatchWithoutTouchingOrders() {
        when(bridge.historyDeals(anyLong(), anyLong())).thenReturn(List.of(deal(7001L, 5.0)));
        TradeOrder local = closedOrder(7001L, "3.00");
        when(tradeOrderMapper.findLatestByTicket(7001L)).thenReturn(local);
        when(tradeOrderMapper.listAll()).thenReturn(List.of(local));

        ReconcileService.ReconcileReport report = service.reconcile();

        assertFalse(report.consistent());
        assertTrue(report.diffs().stream()
                .anyMatch(d -> d.type().equals("PROFIT_MISMATCH") && d.ticket() == 7001L));
        assertEquals(1, report.mt5DealCount());

        verify(auditService).write(eq("RECONCILE"), eq("RECONCILE_DIFF"), anyString(), isNull(),
                anyString(), isNull());
        verify(alertService).reconcileDiff(anyString());
        // 只报告不改账：绝不 insert / update trade_order
        verify(tradeOrderMapper, never()).update(any());
        verify(tradeOrderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("对账：本地与 MT5 一致 → 无差异，不写审计、不告警")
    void reconcileConsistentProducesNoReport() {
        when(bridge.historyDeals(anyLong(), anyLong())).thenReturn(List.of(deal(7001L, 5.0)));
        TradeOrder local = closedOrder(7001L, "5.00");
        when(tradeOrderMapper.findLatestByTicket(7001L)).thenReturn(local);
        when(tradeOrderMapper.listAll()).thenReturn(List.of(local));

        ReconcileService.ReconcileReport report = service.reconcile();

        assertTrue(report.consistent());
        verify(auditService, never()).write(any(), any(), any(), any(), any(), any());
        verify(alertService, never()).reconcileDiff(anyString());
    }

    @Test
    @DisplayName("对账：MT5 有成交但本地无流水 → 报 MT5_DEAL_WITHOUT_LOCAL")
    void reconcileReportsMt5DealWithoutLocal() {
        when(bridge.historyDeals(anyLong(), anyLong())).thenReturn(List.of(deal(8888L, 10.0)));
        when(tradeOrderMapper.findLatestByTicket(8888L)).thenReturn(null);
        when(tradeOrderMapper.listAll()).thenReturn(List.of());

        ReconcileService.ReconcileReport report = service.reconcile();

        assertTrue(report.diffs().stream()
                .anyMatch(d -> d.type().equals("MT5_DEAL_WITHOUT_LOCAL") && d.ticket() == 8888L));
    }

    @Test
    @DisplayName("对账：本地窗口内有流水但 MT5 无成交 → 报 LOCAL_WITHOUT_MT5_DEAL（幽灵记录）")
    void reconcileReportsLocalWithoutMt5Deal() {
        when(bridge.historyDeals(anyLong(), anyLong())).thenReturn(List.of());
        TradeOrder ghost = new TradeOrder();
        ghost.setTicket(9999L);
        ghost.setStatus("FILLED");
        ghost.setOpenTime(LocalDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC));
        when(tradeOrderMapper.listAll()).thenReturn(List.of(ghost));

        ReconcileService.ReconcileReport report = service.reconcile();

        assertTrue(report.diffs().stream()
                .anyMatch(d -> d.type().equals("LOCAL_WITHOUT_MT5_DEAL") && d.ticket() == 9999L));
    }

    // ==================== 辅助 ====================

    private static Position position(long ticket) {
        return new Position(ticket, "GOLD", "buy", 0.01, 4200.0, 4200.0, 0.0, 0.0, 0.0, 0.0, 0L, "",
                "2026-10-12 16:00");
    }

    /** 构造一笔 MT5 平仓成交（entry=1 OUT）。 */
    private static Deal deal(long positionId, double profit) {
        return new Deal(1L, 1L, positionId, "GOLD", "buy", 1, 0.01, 4205.0, profit, 0.0, 0.0,
                "2026-10-13 10:00", "");
    }

    private static TradeOrder closedOrder(long ticket, String profit) {
        TradeOrder o = new TradeOrder();
        o.setTicket(ticket);
        o.setSymbol("GOLD");
        o.setCmd("BUY");
        o.setVolume(new BigDecimal("0.01"));
        o.setStatus("CLOSED");
        o.setProfit(new BigDecimal(profit));
        o.setSwap(BigDecimal.ZERO);
        return o;
    }
}
