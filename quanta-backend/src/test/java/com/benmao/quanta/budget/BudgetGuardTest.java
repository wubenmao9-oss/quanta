package com.benmao.quanta.budget;

import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.config.ConfigService;
import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.entity.SysState;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.OperationAuditMapper;
import com.benmao.quanta.notify.NotifyService;
import com.benmao.quanta.risk.RiskAction;
import com.benmao.quanta.risk.RiskProperties;
import com.benmao.quanta.state.SystemStateService;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BudgetGuard 单测（M12，设计 §8.4）：
 * 按日聚合比较 / 超限降级（发邮件 + 全关触发）/ 平盈利仓审计 TOKEN_EXHAUSTED /
 * 保留仓补挂 HARD SL / 幂等可重入 / 次日与人工恢复。
 *
 * <p>用固定时钟锁定「当日」，Mockito 桩注入各依赖；持仓处置经 {@code TradingService}（唯一写出口）。
 */
class BudgetGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private BudgetProperties props;
    private ConfigService configService;
    private SystemStateService stateService;
    private NotifyService notifyService;
    private AgentDecisionLogMapper decisionLogMapper;
    private OperationAuditMapper auditMapper;
    private Mt5BridgeService bridge;
    private TradingService tradingService;
    private RiskProperties riskProps;
    private BudgetGuard guard;

    @BeforeEach
    void setUp() {
        props = new BudgetProperties();
        configService = mock(ConfigService.class);
        stateService = mock(SystemStateService.class);
        notifyService = mock(NotifyService.class);
        decisionLogMapper = mock(AgentDecisionLogMapper.class);
        auditMapper = mock(OperationAuditMapper.class);
        bridge = mock(Mt5BridgeService.class);
        tradingService = mock(TradingService.class);
        riskProps = new RiskProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"), ZONE); // 北京 10:00

        guard = new BudgetGuard(props, configService, stateService, notifyService, decisionLogMapper,
                auditMapper, bridge, tradingService, riskProps, clock);

        // 默认：当日已记录（跨日检查为 no-op）、未降级、无上限预算、无持仓
        when(stateService.current()).thenReturn(stateWithDate(TODAY, false));
        when(stateService.isDegraded()).thenReturn(false);
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(0L);
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(null);
        when(bridge.positions(any())).thenReturn(List.of());
    }

    // ==================== 按日聚合 + 未超限 ====================

    @Test
    @DisplayName("未超限：不降级、不告警，且把当日用量写入 sys_state；聚合窗口为当日 [00:00, 次日 00:00)")
    void notExceededDoesNothing() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1000));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(500L);

        DegradeOutcome outcome = guard.check();

        assertFalse(outcome.triggered());
        assertFalse(outcome.newlyDegraded());
        assertFalse(outcome.notified());
        verify(stateService, never()).markDegraded();
        verify(notifyService, never()).email(any(), any(), any());
        verify(stateService).recordTokenUsage(eq(TODAY), eq(500L));

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(decisionLogMapper).sumTokensBetween(since.capture(), until.capture());
        assertEquals(TODAY.atStartOfDay(), since.getValue());
        assertEquals(TODAY.plusDays(1).atStartOfDay(), until.getValue());
    }

    @Test
    @DisplayName("无预算（未配置 tokenBudgetDaily / 读不到配置）：按无上限处理，绝不误降级")
    void nullBudgetNeverDegrades() {
        when(configService.activeDecrypted(ConfigType.LLM))
                .thenThrow(new RuntimeException("解密失败（密钥可能已更换）"));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(9_999_999L);

        DegradeOutcome outcome = guard.check();

        assertFalse(outcome.triggered());
        verify(stateService, never()).markDegraded();
        BudgetStatus st = guard.status();
        assertNull(st.budget());
        assertFalse(st.exceeded());
    }

    // ==================== 超限降级 ====================

    @Test
    @DisplayName("超限：进入 DEGRADED + 发邮件 + 平盈利仓(审计 TOKEN_EXHAUSTED) + 保留亏损仓并补挂 HARD SL")
    void exceededDegradesAndProtects() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1000));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(1500L);
        when(stateService.isDegraded()).thenReturn(false);
        when(stateService.markDegraded()).thenReturn(true);
        when(bridge.positions(any())).thenReturn(List.of(
                position(1L, 5.0, 0.0, "buy", 4200.0),     // 盈利 → 平
                position(2L, -3.0, 0.0, "buy", 4200.0),    // 亏损、无 SL → 保留 + 补挂
                position(3L, -1.0, 4190.0, "sell", 4200.0) // 亏损、已有 SL → 保留、不补挂
        ));
        when(notifyService.email(any(), any(), any())).thenReturn(true);

        DegradeOutcome outcome = guard.check();

        assertTrue(outcome.triggered());
        assertTrue(outcome.newlyDegraded());
        assertFalse(outcome.alreadyDegraded());
        assertTrue(outcome.notified());
        assertEquals(List.of(1L), outcome.closedTickets());
        assertEquals(List.of(2L, 3L), outcome.keptTickets());
        assertEquals(List.of(2L), outcome.protectedTickets());
        assertTrue(outcome.failures().isEmpty());

        // 平盈利仓：CLOSE 命令、带 ticket、sessionId/planId 均空（规避持仓互斥）
        ArgumentCaptor<TradeCommand> close = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).closePosition(close.capture());
        assertEquals(RiskAction.CLOSE, close.getValue().action());
        assertEquals(1L, close.getValue().ticket());
        assertNull(close.getValue().sessionId());

        // 保留仓补挂 HARD SL：buy 取现价下方固定点数（300 points × 0.01 = 3.0 → 4197.00）
        ArgumentCaptor<TradeCommand> modify = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).modifySlTp(modify.capture());
        assertEquals(RiskAction.MODIFY, modify.getValue().action());
        assertEquals(2L, modify.getValue().ticket());
        assertEquals(0, new BigDecimal("4197.00").compareTo(modify.getValue().sl()));
        assertNull(modify.getValue().tp(), "仅补 HARD SL，不补 TP");

        verify(notifyService).email(eq(props.getNotifyType()), any(), any());

        // 审计：degrade / close / protect 均带 TOKEN_EXHAUSTED 语义
        List<OperationAudit> audits = capturedAudits();
        assertTrue(audits.stream().anyMatch(a -> a.getOpType().contains("degrade")
                && a.getAfterVal().contains(BudgetGuard.REASON)));
        assertTrue(audits.stream().anyMatch(a -> a.getOpType().contains("close")
                && a.getAfterVal().contains(BudgetGuard.REASON)));
        assertTrue(audits.stream().anyMatch(a -> a.getOpType().contains("protect")));
        assertTrue(audits.stream().allMatch(a -> props.getAuditOperator().equals(a.getOperator())));
    }

    @Test
    @DisplayName("已降级：不重复发邮件，但处置仍幂等执行（可重入）")
    void alreadyDegradedNoDuplicateEmail() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1000));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(1500L);
        when(stateService.isDegraded()).thenReturn(true);
        when(stateService.markDegraded()).thenReturn(false);

        DegradeOutcome outcome = guard.check();

        assertTrue(outcome.triggered());
        assertFalse(outcome.newlyDegraded());
        assertTrue(outcome.alreadyDegraded());
        assertFalse(outcome.notified());
        verify(notifyService, never()).email(any(), any(), any());
        verify(auditMapper, never()).insert(any()); // 未发生状态变化，无 degrade 审计
    }

    @Test
    @DisplayName("连续两次检查：首次降级告警，再次因已降级不再告警（可重入）")
    void reentrantAcrossTwoChecks() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1000));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(1500L);
        // check#1：computeStatus 读 isDegraded=false，degrade 读 isDegraded=false，markDegraded=true
        // check#2：computeStatus 读 isDegraded=true，degrade 读 isDegraded=true，markDegraded=false
        when(stateService.isDegraded()).thenReturn(false, false, true, true);
        when(stateService.markDegraded()).thenReturn(true, false);
        when(notifyService.email(any(), any(), any())).thenReturn(true);

        DegradeOutcome first = guard.check();
        DegradeOutcome second = guard.check();

        assertTrue(first.newlyDegraded());
        assertTrue(first.notified());
        assertFalse(second.newlyDegraded());
        assertFalse(second.notified());
        verify(notifyService, times(1)).email(any(), any(), any());
    }

    @Test
    @DisplayName("平仓失败被逐条记录，不中断其它处置")
    void closeFailureIsRecorded() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(10L);
        when(stateService.markDegraded()).thenReturn(true);
        when(bridge.positions(any())).thenReturn(List.of(
                position(1L, 5.0, 0.0, "buy", 4200.0),
                position(2L, -1.0, 0.0, "buy", 4200.0)));
        when(tradingService.closePosition(any())).thenThrow(new RuntimeException("券商拒绝"));
        when(notifyService.email(any(), any(), any())).thenReturn(true);

        DegradeOutcome outcome = guard.check();

        assertTrue(outcome.triggered());
        assertTrue(outcome.closedTickets().isEmpty());
        assertEquals(1, outcome.failures().size());
        assertTrue(outcome.failures().get(0).contains("平仓 ticket=1"));
        assertEquals(List.of(2L), outcome.protectedTickets(), "其它处置不受影响");
    }

    // ==================== 恢复 ====================

    @Test
    @DisplayName("人工重置：解除降级并审计（幂等）")
    void manualReset() {
        when(stateService.clearDegraded()).thenReturn(true);

        assertTrue(guard.reset("manual"));
        List<OperationAudit> audits = capturedAudits();
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).getOpType().contains("reset"));
        assertTrue(audits.get(0).getAfterVal().contains("RESET"));

        when(stateService.clearDegraded()).thenReturn(false);
        assertFalse(guard.reset("manual"), "本未降级，重置返回 false");
    }

    @Test
    @DisplayName("次日自动恢复：跨日清降级 + 用量归零（审计 NEW_DAY）")
    void autoRecoverOnNewDay() {
        when(stateService.current()).thenReturn(stateWithDate(TODAY.minusDays(1), true));
        when(stateService.clearDegraded()).thenReturn(true);
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(7L);

        DegradeOutcome outcome = guard.check();

        assertFalse(outcome.triggered());
        verify(stateService).clearDegraded();
        verify(stateService).recordTokenUsage(eq(TODAY), eq(0L));
        List<OperationAudit> audits = capturedAudits();
        assertTrue(audits.stream().anyMatch(a -> a.getOpType().contains("reset")
                && a.getAfterVal().contains("NEW_DAY")));
    }

    // ==================== 状态视图 ====================

    @Test
    @DisplayName("status：反映已用/预算/是否超限/剩余/比例")
    void statusView() {
        when(configService.activeDecrypted(ConfigType.LLM)).thenReturn(Map.of("tokenBudgetDaily", 1000));
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(1200L);

        BudgetStatus st = guard.status();

        assertEquals(TODAY, st.tokenDate());
        assertEquals(1200L, st.usedTokens());
        assertEquals(1000L, st.budget());
        assertTrue(st.exceeded());
        assertFalse(st.degraded());
        assertEquals(1.2, st.usedRatio(), 1e-9);
        assertEquals(0L, st.remaining(), "超限时剩余不小于 0");
    }

    @Test
    @DisplayName("usedTokensToday：返回当日聚合用量")
    void usedTokensToday() {
        when(decisionLogMapper.sumTokensBetween(any(), any())).thenReturn(321L);
        assertEquals(321L, guard.usedTokensToday());
    }

    // ==================== 辅助 ====================

    private static SysState stateWithDate(LocalDate date, boolean degraded) {
        SysState s = new SysState();
        s.setId(1L);
        s.setRunMode("AUTO");
        s.setDegraded(degraded);
        s.setTokenDate(date);
        s.setTokenUsedToday(0L);
        return s;
    }

    private static Position position(long ticket, double profit, double sl, String type, double price) {
        return new Position(ticket, "GOLD", type, 0.01, price, price, sl, 0, profit, 0.0, 0, "", "2026-10-13 10:00");
    }

    private List<OperationAudit> capturedAudits() {
        ArgumentCaptor<OperationAudit> captor = ArgumentCaptor.forClass(OperationAudit.class);
        verify(auditMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}
