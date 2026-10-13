package com.benmao.quanta.ops;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskOrigin;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.schedule.TriggerEngine;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工手动交易入口单测（M13，设计 §8.3）：命令置 MANUAL + 成败均落 operation_audit + 通知会话。
 */
class ManualTradeServiceTest {

    private TradingService tradingService;
    private OperationAuditService auditService;
    private TriggerEngine triggerEngine;
    private OpsProperties props;
    private ManualTradeService service;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        auditService = mock(OperationAuditService.class);
        triggerEngine = mock(TriggerEngine.class);
        props = new OpsProperties();
        service = new ManualTradeService(tradingService, auditService, triggerEngine, props);
    }

    @Test
    @DisplayName("人工开仓：命令来源置 MANUAL、透传 TradingService、成功写审计并通知会话")
    void openMarksManualAndAudits() {
        when(tradingService.openPosition(any())).thenReturn(order("FILLED", 7001L, "t1"));

        TradeOrder out = service.open(openCmd("t1"));

        assertEquals("FILLED", out.getStatus());
        ArgumentCaptor<TradeCommand> cap = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).openPosition(cap.capture());
        assertEquals(RiskOrigin.MANUAL, cap.getValue().origin(), "人工入口必须把来源置为 MANUAL");

        verify(auditService).write(eq("MANUAL"), eq("MANUAL_OPEN"), eq("GOLD"), isNull(),
                contains("FILLED"), eq("t1"));
        verify(triggerEngine).systemEvent(eq("人工操作"), anyString());
    }

    @Test
    @DisplayName("人工平仓：target 用 ticket、opType=MANUAL_CLOSE")
    void closeUsesTicketAsTarget() {
        when(tradingService.closePosition(any())).thenReturn(order("CLOSED", 7001L, "t2"));

        service.close(new TradeCommand(RiskAction.CLOSE, null, null, null, null, null, null, null,
                7001L, null, null, "t2"));

        verify(auditService).write(eq("MANUAL"), eq("MANUAL_CLOSE"), eq("7001"), isNull(),
                anyString(), eq("t2"));
    }

    @Test
    @DisplayName("人工操作失败：写 FAILED 审计并原样抛出（失败也留痕）")
    void failureStillAuditsAndRethrows() {
        when(tradingService.openPosition(any()))
                .thenThrow(new BizException(40000, "手数越界", 400));

        assertThrows(BizException.class, () -> service.open(openCmd("t3")));

        verify(auditService).write(eq("MANUAL"), eq("MANUAL_OPEN"), eq("GOLD"), isNull(),
                startsWith("FAILED:"), eq("t3"));
        verify(triggerEngine, never()).systemEvent(anyString(), anyString());
    }

    @Test
    @DisplayName("通知会话关闭时：只写审计、不发 T7 系统事件")
    void notifySessionDisabled() {
        props.setManualNotifySession(false);
        when(tradingService.openPosition(any())).thenReturn(order("FILLED", 7001L, "t4"));

        service.open(openCmd("t4"));

        verify(auditService).write(any(), eq("MANUAL_OPEN"), any(), any(), any(), eq("t4"));
        verify(triggerEngine, never()).systemEvent(anyString(), anyString());
    }

    private static TradeCommand openCmd(String traceId) {
        return new TradeCommand(RiskAction.OPEN, "GOLD", RiskSide.BUY, new BigDecimal("0.01"),
                null, new BigDecimal("4189.23"), null, "SOFT_TP", null, 7L, 9L, traceId);
    }

    private static TradeOrder order(String status, long ticket, String traceId) {
        TradeOrder o = new TradeOrder();
        o.setStatus(status);
        o.setTicket(ticket);
        o.setSymbol("GOLD");
        o.setCmd("BUY");
        o.setVolume(new BigDecimal("0.01"));
        o.setPrice(new BigDecimal("4194.23"));
        o.setTraceId(traceId);
        return o;
    }
}
