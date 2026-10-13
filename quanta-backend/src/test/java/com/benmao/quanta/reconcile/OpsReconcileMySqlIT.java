package com.benmao.quanta.reconcile;

import com.benmao.mt5.model.Deal;
import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.OperationAuditMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.notify.NotifyService;
import com.benmao.quanta.ops.AlertService;
import com.benmao.quanta.ops.OperationAuditService;
import com.benmao.quanta.ops.OpsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M13 认领与对账真库集成测试：真实读写 {@code trade_order} / {@code operation_audit}。
 *
 * <p>确定性保障：桥接 {@link Mt5BridgeService} 用 mock 提供持仓/成交（不依赖真实 MT5）；
 * 告警用 mock 的 {@link NotifyService}，<b>不发真实邮件</b>；测试 profile 已关闭后台定时对账与启动认领，
 * 用例直接构造 {@link ReconcileService} 调用，行为可控。
 *
 * <p>验证点（对应 M13 验收）：
 * <ol>
 *   <li>认领孤儿持仓 → 真库补建 status=FILLED / trace_id=CLAIM-&lt;ticket&gt; 的认领单，并落 operation_audit(CLAIM)；</li>
 *   <li>对账差异 → 落 operation_audit(RECONCILE_DIFF)，且 <b>trade_order 原样不动</b>（只报告不改账）。</li>
 * </ol>
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=OpsReconcileMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class OpsReconcileMySqlIT {

    private static final long CLAIM_TICKET = 990001L;
    private static final long RECON_TICKET = 990002L;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private OperationAuditMapper operationAuditMapper;

    private ReconcileService service;

    /** 审计基线：清理时只删本用例新写入的审计行。 */
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        Mt5BridgeService bridge = mock(Mt5BridgeService.class);
        ReconcileProperties props = new ReconcileProperties();
        props.setEnabled(true);
        props.setClaimOnStartup(true);
        props.setNotifyOnDiff(true);
        AlertService alertService = new AlertService(mock(NotifyService.class), new OpsProperties());
        service = new ReconcileService(bridge, tradeOrderMapper, operationAuditService, alertService, props,
                Clock.systemUTC());

        purgeTicket(CLAIM_TICKET);
        purgeTicket(RECON_TICKET);
        auditBaseline = maxAuditId();

        when(bridge.positions(isNull())).thenReturn(List.of(
                new Position(CLAIM_TICKET, "GOLD", "buy", 0.01, 4200.0, 4200.0, 0.0, 0.0, 0.0, 0.0, 0L, "",
                        "2026-10-13 10:00:00")));
        when(bridge.historyDeals(anyLong(), anyLong()))
                .thenReturn(List.of(new Deal(1L, 1L, RECON_TICKET, "GOLD", "buy", 1, 0.01, 4205.0,
                        5.0, 0.0, 0.0, "2026-10-13 10:00", "")));
    }

    @AfterEach
    void cleanup() {
        purgeTicket(CLAIM_TICKET);
        purgeTicket(RECON_TICKET);
        operationAuditMapper.listAll().stream()
                .filter(a -> a.getId() != null && a.getId() > auditBaseline)
                .forEach(a -> operationAuditMapper.deleteById(a.getId()));
    }

    @Test
    @DisplayName("认领：MT5 有持仓而本地无流水 → 真库补建认领单并落 operation_audit(CLAIM)")
    void claimInsertsOrderAndAuditToRealDb() {
        List<Long> claimed = service.claimExistingPositions();

        assertTrue(claimed.contains(CLAIM_TICKET));

        TradeOrder stored = tradeOrderMapper.findLatestByTicket(CLAIM_TICKET);
        assertNotNull(stored, "认领单应落库");
        assertEquals("CLAIM-" + CLAIM_TICKET, stored.getTraceId());
        assertEquals("FILLED", stored.getStatus());
        assertEquals("NONE", stored.getTpMode());
        assertEquals("GOLD", stored.getSymbol());
        assertEquals(0, new BigDecimal("0.01").compareTo(stored.getVolume()));

        OperationAudit audit = findNewAudit("CLAIM");
        assertNotNull(audit, "认领应落 operation_audit(CLAIM)");
        assertEquals("RECONCILE", audit.getOperator());
        assertEquals(String.valueOf(CLAIM_TICKET), audit.getTarget());
        assertEquals("CLAIM-" + CLAIM_TICKET, audit.getTraceId());
    }

    @Test
    @DisplayName("对账：盈亏不符 → 落 operation_audit(RECONCILE_DIFF)，trade_order 原样不动")
    void reconcileDiffAuditsButLeavesOrderUnchanged() {
        TradeOrder local = new TradeOrder();
        local.setTraceId("IT-RECON-" + RECON_TICKET);
        local.setTicket(RECON_TICKET);
        local.setSymbol("GOLD");
        local.setCmd("BUY");
        local.setVolume(new BigDecimal("0.01"));
        local.setStatus("CLOSED");
        local.setProfit(new BigDecimal("3.00"));
        local.setSwap(BigDecimal.ZERO);
        local.setOpenTime(LocalDateTime.now().minusHours(1));
        local.setCloseTime(LocalDateTime.now().minusHours(1));
        tradeOrderMapper.insert(local);

        ReconcileService.ReconcileReport report = service.reconcile();

        assertFalse(report.consistent());
        assertTrue(report.diffs().stream()
                .anyMatch(d -> d.type().equals("PROFIT_MISMATCH") && d.ticket() == RECON_TICKET));

        OperationAudit audit = findNewAudit("RECONCILE_DIFF");
        assertNotNull(audit, "对账差异应落 operation_audit(RECONCILE_DIFF)");
        assertEquals("RECONCILE", audit.getOperator());

        // 只报告不改账：本地流水保持原样
        TradeOrder after = tradeOrderMapper.findLatestByTicket(RECON_TICKET);
        assertEquals("CLOSED", after.getStatus());
        assertEquals(0, new BigDecimal("3.00").compareTo(after.getProfit()));
    }

    // ==================== 辅助 ====================

    private void purgeTicket(long ticket) {
        for (TradeOrder o : tradeOrderMapper.listAll()) {
            if (o.getTicket() != null && o.getTicket() == ticket) {
                tradeOrderMapper.deleteById(o.getId());
            }
        }
    }

    private long maxAuditId() {
        return operationAuditMapper.listAll().stream()
                .map(OperationAudit::getId).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(0L);
    }

    /** 本用例新写入的指定 op_type 的最新一条审计。 */
    private OperationAudit findNewAudit(String opType) {
        return operationAuditMapper.listAll().stream()
                .filter(a -> a.getId() != null && a.getId() > auditBaseline)
                .filter(a -> opType.equals(a.getOpType()))
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
