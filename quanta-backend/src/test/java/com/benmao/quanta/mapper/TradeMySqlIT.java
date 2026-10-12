package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.entity.TradeReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * trade_plan / trade_order / agent_decision_log / trade_report 真库集成测试。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=TradeMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class TradeMySqlIT {

    @Autowired
    private TradePlanMapper tradePlanMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private AgentDecisionLogMapper agentDecisionLogMapper;

    @Autowired
    private TradeReportMapper tradeReportMapper;

    @Test
    void tradePlan_insertUpdateDelete() {
        TradePlan e = new TradePlan();
        e.setSessionId(11L);
        e.setPlanType("PRICE");
        e.setTargetPrice(new BigDecimal("2000.12345"));
        e.setInvalidCond("price invalid");
        e.setExpireAt(LocalDateTime.of(2024, 5, 6, 7, 8, 9));
        e.setStatus("PENDING");

        tradePlanMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        TradePlan loaded = tradePlanMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getSessionId(), loaded.getSessionId());
        assertEquals(e.getPlanType(), loaded.getPlanType());
        assertEquals(0, e.getTargetPrice().compareTo(loaded.getTargetPrice()));
        assertEquals(e.getInvalidCond(), loaded.getInvalidCond());
        assertEquals(e.getExpireAt(), loaded.getExpireAt());
        assertEquals(e.getStatus(), loaded.getStatus());

        loaded.setStatus("TRIGGERED");
        loaded.setInvalidCond("price invalid updated");
        tradePlanMapper.update(loaded);
        TradePlan updated = tradePlanMapper.getById(e.getId());
        assertEquals("TRIGGERED", updated.getStatus());
        assertEquals("price invalid updated", updated.getInvalidCond());

        tradePlanMapper.deleteById(e.getId());
        assertNull(tradePlanMapper.getById(e.getId()));
    }

    @Test
    void tradeOrder_insertUpdateDelete() {
        TradeOrder e = new TradeOrder();
        e.setPlanId(21L);
        e.setSessionId(22L);
        e.setTraceId("trace-" + System.nanoTime());
        e.setTicket(333L);
        e.setSymbol("GOLD");
        e.setCmd("BUY");
        e.setVolume(new BigDecimal("0.10"));
        e.setPrice(new BigDecimal("2010.50000"));
        e.setSl(new BigDecimal("2000.00000"));
        e.setTp(new BigDecimal("2030.00000"));
        e.setTpMode("SOFT");
        e.setTrailMode("NONE");
        e.setStatus("FILLED");
        e.setRetcode(10009);
        e.setProfit(new BigDecimal("12.34"));
        e.setSwap(new BigDecimal("-1.20"));
        e.setOpenTime(LocalDateTime.of(2024, 6, 7, 8, 9, 10));
        e.setClosePrice(new BigDecimal("2011.00000"));
        e.setCloseTime(LocalDateTime.of(2024, 6, 7, 9, 10, 11));

        tradeOrderMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        TradeOrder loaded = tradeOrderMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getPlanId(), loaded.getPlanId());
        assertEquals(e.getSessionId(), loaded.getSessionId());
        assertEquals(e.getTraceId(), loaded.getTraceId());
        assertEquals(e.getTicket(), loaded.getTicket());
        assertEquals(e.getSymbol(), loaded.getSymbol());
        assertEquals(e.getCmd(), loaded.getCmd());
        assertEquals(0, e.getVolume().compareTo(loaded.getVolume()));
        assertEquals(0, e.getPrice().compareTo(loaded.getPrice()));
        assertEquals(0, e.getSl().compareTo(loaded.getSl()));
        assertEquals(0, e.getTp().compareTo(loaded.getTp()));
        assertEquals(e.getTpMode(), loaded.getTpMode());
        assertEquals(e.getTrailMode(), loaded.getTrailMode());
        assertEquals(e.getStatus(), loaded.getStatus());
        assertEquals(e.getRetcode(), loaded.getRetcode());
        assertEquals(0, e.getProfit().compareTo(loaded.getProfit()));
        assertEquals(0, e.getSwap().compareTo(loaded.getSwap()));
        assertEquals(e.getOpenTime(), loaded.getOpenTime());
        assertEquals(0, e.getClosePrice().compareTo(loaded.getClosePrice()));
        assertEquals(e.getCloseTime(), loaded.getCloseTime());

        loaded.setStatus("CLOSED");
        loaded.setProfit(new BigDecimal("20.00"));
        tradeOrderMapper.update(loaded);
        TradeOrder updated = tradeOrderMapper.getById(e.getId());
        assertEquals("CLOSED", updated.getStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(updated.getProfit()));

        tradeOrderMapper.deleteById(e.getId());
        assertNull(tradeOrderMapper.getById(e.getId()));
    }

    @Test
    void agentDecisionLog_insertUpdateDelete() {
        AgentDecisionLog e = new AgentDecisionLog();
        e.setSessionId(31L);
        e.setTraceId("trace-" + System.nanoTime());
        e.setTriggerType("T1");
        e.setInputSnapshot("{\"snapshot\": 1}");
        e.setModel("gpt-4o");
        e.setPromptVersion(3);
        e.setAction("OPEN");
        e.setActionParams("{\"volume\": 1}");
        e.setConfidence(new BigDecimal("0.85"));
        e.setReason("it reason");
        e.setTokens(1200);
        e.setLatencyMs(850);

        agentDecisionLogMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        AgentDecisionLog loaded = agentDecisionLogMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getSessionId(), loaded.getSessionId());
        assertEquals(e.getTraceId(), loaded.getTraceId());
        assertEquals(e.getTriggerType(), loaded.getTriggerType());
        assertEquals(e.getInputSnapshot(), loaded.getInputSnapshot());
        assertEquals(e.getModel(), loaded.getModel());
        assertEquals(e.getPromptVersion(), loaded.getPromptVersion());
        assertEquals(e.getAction(), loaded.getAction());
        assertEquals(e.getActionParams(), loaded.getActionParams());
        assertEquals(0, e.getConfidence().compareTo(loaded.getConfidence()));
        assertEquals(e.getReason(), loaded.getReason());
        assertEquals(e.getTokens(), loaded.getTokens());
        assertEquals(e.getLatencyMs(), loaded.getLatencyMs());

        loaded.setAction("CLOSE");
        loaded.setLatencyMs(900);
        agentDecisionLogMapper.update(loaded);
        AgentDecisionLog updated = agentDecisionLogMapper.getById(e.getId());
        assertEquals("CLOSE", updated.getAction());
        assertEquals(900, updated.getLatencyMs());

        agentDecisionLogMapper.deleteById(e.getId());
        assertNull(agentDecisionLogMapper.getById(e.getId()));
    }

    @Test
    void tradeReport_insertUpdateDelete() {
        TradeReport e = new TradeReport();
        e.setOrderId(41L);
        e.setSessionId(42L);
        e.setActionTimeline("{\"step\": \"open\"}");
        e.setSummary("it summary");
        e.setPnl(new BigDecimal("15.50"));
        e.setResult("WIN");
        e.setReviewed(Boolean.FALSE);

        tradeReportMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        TradeReport loaded = tradeReportMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getOrderId(), loaded.getOrderId());
        assertEquals(e.getSessionId(), loaded.getSessionId());
        assertEquals(e.getActionTimeline(), loaded.getActionTimeline());
        assertEquals(e.getSummary(), loaded.getSummary());
        assertEquals(0, e.getPnl().compareTo(loaded.getPnl()));
        assertEquals(e.getResult(), loaded.getResult());
        assertEquals(e.getReviewed(), loaded.getReviewed());

        loaded.setReviewed(Boolean.TRUE);
        loaded.setResult("LOSS");
        tradeReportMapper.update(loaded);
        TradeReport updated = tradeReportMapper.getById(e.getId());
        assertEquals(Boolean.TRUE, updated.getReviewed());
        assertEquals("LOSS", updated.getResult());

        tradeReportMapper.deleteById(e.getId());
        assertNull(tradeReportMapper.getById(e.getId()));
    }
}