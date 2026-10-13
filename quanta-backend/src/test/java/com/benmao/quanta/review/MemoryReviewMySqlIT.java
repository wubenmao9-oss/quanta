package com.benmao.quanta.review;

import com.benmao.quanta.agent.AgentDecideRequest;
import com.benmao.quanta.agent.AgentDecideResult;
import com.benmao.quanta.agent.AgentService;
import com.benmao.quanta.entity.AgentMemory;
import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradeReport;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.AgentMemoryMapper;
import com.benmao.quanta.mapper.AgentSessionMapper;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.mapper.TradeReportMapper;
import com.benmao.quanta.memory.MemoryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M11 记忆/复盘真库集成测试：真实落 {@code agent_memory} / {@code trade_report}，并用真实 {@code backtest_run} 做成本拆解。
 *
 * <p>确定性保障（同 M10 IT）：用 {@link TestPropertySource} 换掉 AES 密钥 → 库内 LLM 配置解密失败，
 * {@code AgentService.decide} 走 fail-closed 降级 WAIT，<b>不依赖外网大模型</b>，但每一步仍真实落库决策日志。
 *
 * <p>验证点：
 * <ol>
 *   <li>会话内决策数达 {@code summary-every} 整数倍 → <b>自动产出记忆卡并落库</b>（验收①）；</li>
 *   <li>平仓事件（AFTER_COMMIT）→ <b>自动生成单笔全周期报告</b>，pnl/result/时间线齐备（验收②）；</li>
 *   <li>真实回测明细的<b>成本拆解</b>：点差/滑点/swap 占比与「是否被点差吃掉利润」判定（验收③）。</li>
 * </ol>
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=MemoryReviewMySqlIT "-Dsurefire.failIfNoSpecifiedTests=true"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
@TestPropertySource(properties = {
        "quanta.security.aes-key=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=",
        "quanta.memory.summary-every=5"
})
class MemoryReviewMySqlIT {

    @Autowired
    private AgentService agentService;

    @Autowired
    private MemoryProperties memoryProps;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private AgentDecisionLogMapper decisionLogMapper;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private AgentMemoryMapper memoryMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private TradeReportMapper tradeReportMapper;

    @Autowired
    private BacktestRunMapper backtestRunMapper;

    @Autowired
    private BacktestTradeMapper backtestTradeMapper;

    @Autowired
    private StrategyMapper strategyMapper;

    private final List<Long> createdLogIds = new ArrayList<>();
    private final List<Long> createdSessionIds = new ArrayList<>();
    private final List<Long> createdMemoryIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdRunIds = new ArrayList<>();
    private final List<Long> createdTradeIds = new ArrayList<>();

    @BeforeEach
    void precondition() {
        boolean hasActive = strategyMapper.listAll().stream()
                .anyMatch(s -> "ACTIVE".equalsIgnoreCase(s.getStatus())
                        && s.getPrompt() != null && !s.getPrompt().isBlank());
        assertTrue(hasActive, "前置：需存在含 Prompt 的 ACTIVE 策略（M9 已批准 id 13/14）");
    }

    @AfterEach
    void cleanup() {
        createdReportIds.forEach(tradeReportMapper::deleteById);
        createdTradeIds.forEach(backtestTradeMapper::deleteById);
        createdRunIds.forEach(backtestRunMapper::deleteById);
        createdOrderIds.forEach(tradeOrderMapper::deleteById);
        createdMemoryIds.forEach(memoryMapper::deleteById);
        createdLogIds.forEach(decisionLogMapper::deleteById);
        createdSessionIds.forEach(sessionMapper::deleteById);
        createdReportIds.clear();
        createdTradeIds.clear();
        createdRunIds.clear();
        createdOrderIds.clear();
        createdMemoryIds.clear();
        createdLogIds.clear();
        createdSessionIds.clear();
    }

    // ==================== ① 记忆卡自动产出 ====================

    @Test
    @DisplayName("会话内决策数达 summary-every 整数倍 → 自动产出记忆卡并落库（验收①）")
    void autoProducesMemoryCardAtInterval() {
        assertEquals(5, memoryProps.getSummaryEvery(), "IT 前置：summary-every 应为 5");

        AgentDecideResult first = agentService.decide(new AgentDecideRequest("GOLD", "M5", null, null, "T6", "IT-M11-1"));
        trackDecision(first);
        Long sessionId = first.sessionId();
        assertNotNull(sessionId);

        // 同一会话再研判 4 次，使会话内累计决策数达到 5（= summary-every 整数倍）
        for (int i = 2; i <= 5; i++) {
            trackDecision(agentService.decide(
                    new AgentDecideRequest("GOLD", "M5", null, sessionId, "T6", "IT-M11-" + i)));
        }

        List<AgentMemory> cards = findCardsForSession(sessionId);
        assertFalse(cards.isEmpty(), "第 5 次决策后应自动产出记忆卡");

        AgentMemory card = cards.get(0);
        createdMemoryIds.add(card.getId());
        assertEquals("STRATEGY", card.getScope());
        assertEquals("GOLD", card.getSymbol());
        assertTrue(card.getSummary().contains("累计决策 5 次"), card.getSummary());
        assertTrue(card.getTags().contains("session:" + sessionId));
    }

    // ==================== ② 平仓自动生成报告 ====================

    @Test
    @DisplayName("平仓事件（AFTER_COMMIT）→ 自动生成单笔全周期报告（验收②）")
    void generatesReportOnTradeClosed() {
        TradeOrder order = insertClosedOrder();
        createdOrderIds.add(order.getId());

        // 在事务内发布事件 → AFTER_COMMIT 监听器在提交后生成报告（独立事务）
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> publisher.publishEvent(new TradeClosedEvent(order)));

        TradeReport report = tradeReportMapper.findByOrderId(order.getId());
        assertNotNull(report, "平仓提交后应自动生成 trade_report");
        createdReportIds.add(report.getId());
        assertEquals(0, new BigDecimal("14.25").compareTo(report.getPnl())); // 15.50 + (-1.25)
        assertEquals("WIN", report.getResult());
        assertNotNull(report.getActionTimeline());
        assertTrue(report.getActionTimeline().contains("CLOSE"));
        assertFalse(report.getReviewed());
    }

    @Test
    @DisplayName("单笔报告接口：读回自动生成的报告（验收②）")
    void reportReadableByOrderId() {
        TradeOrder order = insertClosedOrder();
        createdOrderIds.add(order.getId());
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> publisher.publishEvent(new TradeClosedEvent(order)));

        TradeReport report = reviewService.getReport(order.getId());
        createdReportIds.add(report.getId());
        assertEquals(order.getId(), report.getOrderId());
        assertEquals("WIN", report.getResult());
    }

    // ==================== ③ 成本拆解 ====================

    @Test
    @DisplayName("真实回测明细 → 点差/滑点/swap 拆解与「是否被点差吃掉利润」判定（验收③）")
    void analyzesCostFromRealBacktest() {
        BacktestRun run = insertBacktestRun(30, 2, true);
        Long runId = run.getId();
        insertBacktestTrade(runId, "5.0", "-0.5");
        insertBacktestTrade(runId, "5.0", "-0.5");

        CostAnalysis c = reviewService.analyzeCost(runId);

        assertEquals(2, c.tradeCount());
        assertEquals(1.2, c.spreadCost(), 0.0001);
        assertEquals(0.08, c.slippageCost(), 0.0001);
        assertEquals(1.0, c.swapCost(), 0.0001);
        assertEquals(9.0, c.netProfit(), 0.0001);
        assertEquals(11.28, c.grossProfit(), 0.0001);
        assertEquals(2.28, c.totalCost(), 0.0001);
        assertFalse(c.eatenByCost());
        assertNotNull(c.spreadPct());
        assertNotNull(c.disclaimer());
    }

    @Test
    @DisplayName("高点差档位吃掉利润 → eatenByCost=true（验收③）")
    void detectsProfitEatenByCost() {
        BacktestRun run = insertBacktestRun(50, 0, false);
        Long runId = run.getId();
        insertBacktestTrade(runId, "-0.5", "0");

        CostAnalysis c = reviewService.analyzeCost(runId);

        assertEquals(1.0, c.spreadCost(), 0.0001);
        assertEquals(0.5, c.grossProfit(), 0.0001);
        assertEquals(-0.5, c.netProfit(), 0.0001);
        assertTrue(c.eatenByCost());
        assertTrue(c.verdict().contains("被点差吃掉利润"));
    }

    // ==================== 辅助 ====================

    private void trackDecision(AgentDecideResult r) {
        if (r.decisionLogId() != null) {
            createdLogIds.add(r.decisionLogId());
        }
        if (r.sessionId() != null && !createdSessionIds.contains(r.sessionId())) {
            createdSessionIds.add(r.sessionId());
        }
    }

    private List<AgentMemory> findCardsForSession(Long sessionId) {
        String tag = "session:" + sessionId + ",";
        List<AgentMemory> out = new ArrayList<>();
        for (AgentMemory m : memoryMapper.listRecent(200)) {
            if (m.getTags() != null && m.getTags().contains(tag)) {
                out.add(m);
            }
        }
        return out;
    }

    private TradeOrder insertClosedOrder() {
        TradeOrder o = new TradeOrder();
        o.setTraceId("it" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setSymbol("GOLD");
        o.setCmd("BUY");
        o.setVolume(new BigDecimal("0.01"));
        o.setPrice(new BigDecimal("4200.00"));
        o.setSl(new BigDecimal("4190.00"));
        o.setTp(new BigDecimal("4230.00"));
        o.setTpMode("SOFT");
        o.setTrailMode("NONE");
        o.setStatus("CLOSED");
        o.setRetcode(0);
        o.setProfit(new BigDecimal("15.50"));
        o.setSwap(new BigDecimal("-1.25"));
        o.setOpenTime(LocalDateTime.now().minusMinutes(5));
        o.setClosePrice(new BigDecimal("4205.60"));
        o.setCloseTime(LocalDateTime.now());
        tradeOrderMapper.insert(o);
        return o;
    }

    private BacktestRun insertBacktestRun(int spread, int slippage, boolean swapOn) {
        BacktestRun run = new BacktestRun();
        run.setStrategyId(13L);
        run.setSymbol("GOLD");
        run.setDataTimeframe("M1");
        run.setStartTime(LocalDateTime.now().minusDays(1));
        run.setEndTime(LocalDateTime.now());
        run.setParams("{}");
        run.setSpreadMode(spread == 0 ? "OFF" : "FIXED");
        run.setSpread(BigDecimal.valueOf(spread));
        run.setSlippage(BigDecimal.valueOf(slippage));
        run.setSwapOn(swapOn);
        run.setMode("RULE");
        run.setStatus("DONE");
        backtestRunMapper.insert(run);
        createdRunIds.add(run.getId());
        return run;
    }

    private void insertBacktestTrade(Long runId, String profit, String swap) {
        BacktestTrade t = new BacktestTrade();
        t.setRunId(runId);
        t.setOpenTime(LocalDateTime.now().minusMinutes(10));
        t.setCloseTime(LocalDateTime.now().minusMinutes(5));
        t.setCmd("BUY");
        t.setVolume(new BigDecimal("0.01"));
        t.setOpenPrice(new BigDecimal("4200.00"));
        t.setClosePrice(new BigDecimal("4201.00"));
        t.setProfit(new BigDecimal(profit));
        t.setSwap(new BigDecimal(swap));
        t.setHoldSec(300);
        backtestTradeMapper.insert(t);
        createdTradeIds.add(t.getId());
    }
}
