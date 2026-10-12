package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AccountSnapshot;
import com.benmao.quanta.entity.NotifyLog;
import com.benmao.quanta.entity.OperationAudit;
import com.benmao.quanta.entity.RiskConfig;
import com.benmao.quanta.entity.SysState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * account_snapshot / risk_config / notify_log / operation_audit / sys_state 真库集成测试。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=OpsMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class OpsMySqlIT {

    @Autowired
    private AccountSnapshotMapper accountSnapshotMapper;

    @Autowired
    private RiskConfigMapper riskConfigMapper;

    @Autowired
    private NotifyLogMapper notifyLogMapper;

    @Autowired
    private OperationAuditMapper operationAuditMapper;

    @Autowired
    private SysStateMapper sysStateMapper;

    @Test
    void accountSnapshot_insertUpdateDelete() {
        AccountSnapshot e = new AccountSnapshot();
        e.setLogin(100001L);
        e.setBalance(new BigDecimal("1000.00"));
        e.setEquity(new BigDecimal("1010.50"));
        e.setMargin(new BigDecimal("100.00"));
        e.setFreeMargin(new BigDecimal("910.50"));
        e.setProfit(new BigDecimal("10.50"));
        e.setCurrency("USD");
        e.setSnapshotTime(LocalDateTime.of(2024, 7, 8, 9, 10, 11));

        accountSnapshotMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        AccountSnapshot loaded = accountSnapshotMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getLogin(), loaded.getLogin());
        assertEquals(0, e.getBalance().compareTo(loaded.getBalance()));
        assertEquals(0, e.getEquity().compareTo(loaded.getEquity()));
        assertEquals(0, e.getMargin().compareTo(loaded.getMargin()));
        assertEquals(0, e.getFreeMargin().compareTo(loaded.getFreeMargin()));
        assertEquals(0, e.getProfit().compareTo(loaded.getProfit()));
        assertEquals(e.getCurrency(), loaded.getCurrency());
        assertEquals(e.getSnapshotTime(), loaded.getSnapshotTime());

        loaded.setCurrency("EUR");
        loaded.setBalance(new BigDecimal("2000.00"));
        accountSnapshotMapper.update(loaded);
        AccountSnapshot updated = accountSnapshotMapper.getById(e.getId());
        assertEquals("EUR", updated.getCurrency());
        assertEquals(0, new BigDecimal("2000.00").compareTo(updated.getBalance()));

        accountSnapshotMapper.deleteById(e.getId());
        assertNull(accountSnapshotMapper.getById(e.getId()));
    }

    @Test
    void riskConfig_insertUpdateDelete() {
        RiskConfig e = new RiskConfig();
        e.setFixedVolume(new BigDecimal("0.01"));
        e.setMaxVolume(new BigDecimal("1.00"));
        e.setMaxPositions(3);
        e.setMaxDailyLoss(new BigDecimal("50.00"));
        e.setMaxRiskPct(new BigDecimal("2.00"));
        e.setMinRr(new BigDecimal("1.50"));
        e.setMinSlSpreadMult(new BigDecimal("5.00"));
        e.setMaxConsecutiveLoss(3);
        e.setCooldownMinutes(0);
        e.setSpreadLimit(new BigDecimal("50.00"));
        e.setSymbolWhitelist("GOLD");

        riskConfigMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        RiskConfig loaded = riskConfigMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(0, e.getFixedVolume().compareTo(loaded.getFixedVolume()));
        assertEquals(0, e.getMaxVolume().compareTo(loaded.getMaxVolume()));
        assertEquals(e.getMaxPositions(), loaded.getMaxPositions());
        assertEquals(0, e.getMaxDailyLoss().compareTo(loaded.getMaxDailyLoss()));
        assertEquals(0, e.getMaxRiskPct().compareTo(loaded.getMaxRiskPct()));
        assertEquals(0, e.getMinRr().compareTo(loaded.getMinRr()));
        assertEquals(0, e.getMinSlSpreadMult().compareTo(loaded.getMinSlSpreadMult()));
        assertEquals(e.getMaxConsecutiveLoss(), loaded.getMaxConsecutiveLoss());
        assertEquals(e.getCooldownMinutes(), loaded.getCooldownMinutes());
        assertEquals(0, e.getSpreadLimit().compareTo(loaded.getSpreadLimit()));
        assertEquals(e.getSymbolWhitelist(), loaded.getSymbolWhitelist());

        loaded.setMaxPositions(5);
        loaded.setSymbolWhitelist("GOLD,EURUSD");
        riskConfigMapper.update(loaded);
        RiskConfig updated = riskConfigMapper.getById(e.getId());
        assertEquals(5, updated.getMaxPositions());
        assertEquals("GOLD,EURUSD", updated.getSymbolWhitelist());

        riskConfigMapper.deleteById(e.getId());
        assertNull(riskConfigMapper.getById(e.getId()));
    }

    @Test
    void notifyLog_insertUpdateDelete() {
        NotifyLog e = new NotifyLog();
        e.setNotifyType("OPEN");
        e.setChannel("MAIL");
        e.setSubject("it subject");
        e.setContent("it content");
        e.setStatus("SENT");
        e.setError(null);

        notifyLogMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        NotifyLog loaded = notifyLogMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getNotifyType(), loaded.getNotifyType());
        assertEquals(e.getChannel(), loaded.getChannel());
        assertEquals(e.getSubject(), loaded.getSubject());
        assertEquals(e.getContent(), loaded.getContent());
        assertEquals(e.getStatus(), loaded.getStatus());
        assertEquals(e.getError(), loaded.getError());

        loaded.setStatus("FAILED");
        loaded.setError("smtp timeout");
        notifyLogMapper.update(loaded);
        NotifyLog updated = notifyLogMapper.getById(e.getId());
        assertEquals("FAILED", updated.getStatus());
        assertEquals("smtp timeout", updated.getError());

        notifyLogMapper.deleteById(e.getId());
        assertNull(notifyLogMapper.getById(e.getId()));
    }

    @Test
    void operationAudit_insertUpdateDelete() {
        OperationAudit e = new OperationAudit();
        e.setOperator("LOCAL");
        e.setOpType("MANUAL_OPEN");
        e.setTarget("GOLD");
        e.setBeforeVal("PENDING");
        e.setAfterVal("FILLED");
        e.setTraceId("trace-" + System.nanoTime());

        operationAuditMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        OperationAudit loaded = operationAuditMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getOperator(), loaded.getOperator());
        assertEquals(e.getOpType(), loaded.getOpType());
        assertEquals(e.getTarget(), loaded.getTarget());
        assertEquals(e.getBeforeVal(), loaded.getBeforeVal());
        assertEquals(e.getAfterVal(), loaded.getAfterVal());
        assertEquals(e.getTraceId(), loaded.getTraceId());

        loaded.setOpType("MANUAL_CLOSE");
        loaded.setAfterVal("CLOSED");
        operationAuditMapper.update(loaded);
        OperationAudit updated = operationAuditMapper.getById(e.getId());
        assertEquals("MANUAL_CLOSE", updated.getOpType());
        assertEquals("CLOSED", updated.getAfterVal());

        operationAuditMapper.deleteById(e.getId());
        assertNull(operationAuditMapper.getById(e.getId()));
    }

    @Test
    void sysState_insertUpdateDelete() {
        SysState e = new SysState();
        e.setRunMode("AUTO");
        e.setDegraded(Boolean.FALSE);
        e.setTokenUsedToday(500000L);
        e.setTokenDate(LocalDate.of(2024, 8, 9));
        e.setActiveLlmId(1L);
        e.setActiveStrategyId(2L);

        sysStateMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        SysState loaded = sysStateMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getRunMode(), loaded.getRunMode());
        assertEquals(e.getDegraded(), loaded.getDegraded());
        assertEquals(e.getTokenUsedToday(), loaded.getTokenUsedToday());
        assertEquals(e.getTokenDate(), loaded.getTokenDate());
        assertEquals(e.getActiveLlmId(), loaded.getActiveLlmId());
        assertEquals(e.getActiveStrategyId(), loaded.getActiveStrategyId());

        loaded.setRunMode("PAUSED");
        loaded.setDegraded(Boolean.TRUE);
        sysStateMapper.update(loaded);
        SysState updated = sysStateMapper.getById(e.getId());
        assertEquals("PAUSED", updated.getRunMode());
        assertEquals(Boolean.TRUE, updated.getDegraded());

        sysStateMapper.deleteById(e.getId());
        assertNull(sysStateMapper.getById(e.getId()));
    }
}