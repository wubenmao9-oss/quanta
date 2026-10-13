package com.benmao.quanta.review;

import com.benmao.quanta.backtest.BacktestProperties;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradeReport;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.mapper.TradeReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewService 单测（M11 复盘层）：单笔报告 / 周期统计 / 败因分类 / 策略评价 / 成本拆解。
 */
class ReviewServiceTest {

    private ReviewProperties props;
    private TradeOrderMapper tradeOrderMapper;
    private TradeReportMapper tradeReportMapper;
    private AgentDecisionLogMapper decisionLogMapper;
    private StrategyMapper strategyMapper;
    private BacktestRunMapper backtestRunMapper;
    private BacktestTradeMapper backtestTradeMapper;
    private BacktestProperties backtestProps;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        props = new ReviewProperties();
        tradeOrderMapper = mock(TradeOrderMapper.class);
        tradeReportMapper = mock(TradeReportMapper.class);
        decisionLogMapper = mock(AgentDecisionLogMapper.class);
        strategyMapper = mock(StrategyMapper.class);
        backtestRunMapper = mock(BacktestRunMapper.class);
        backtestTradeMapper = mock(BacktestTradeMapper.class);
        backtestProps = new BacktestProperties();
        service = new ReviewService(props, tradeOrderMapper, tradeReportMapper, decisionLogMapper,
                strategyMapper, backtestRunMapper, backtestTradeMapper, backtestProps, new ObjectMapper());
    }

    // ==================== 单笔复盘 ====================

    @Test
    @DisplayName("单笔报告：pnl=profit+swap、result 判定、动作时间线含 DECISION/OPEN/CLOSE")
    void generateReportBuildsTimeline() {
        TradeOrder o = closed(101L, 7L, "trace-1", "GOLD", "BUY", "3.50", "0.00",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        when(tradeOrderMapper.getById(101L)).thenReturn(o);
        when(tradeReportMapper.findByOrderId(101L)).thenReturn(null);
        when(decisionLogMapper.listByTraceId("trace-1")).thenReturn(List.of());
        when(tradeReportMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, TradeReport.class).setId(500L);
            return 1;
        });

        TradeReport r = service.generateReport(101L);

        assertEquals(500L, r.getId());
        assertEquals(101L, r.getOrderId());
        assertEquals(7L, r.getSessionId());
        assertEquals(0, new BigDecimal("3.50").compareTo(r.getPnl()));
        assertEquals("WIN", r.getResult());
        assertFalse(r.getReviewed());
        assertTrue(r.getActionTimeline().contains("\"type\":\"OPEN\""));
        assertTrue(r.getActionTimeline().contains("\"type\":\"CLOSE\""));
        assertTrue(r.getSummary().contains("WIN"));
    }

    @Test
    @DisplayName("单笔报告幂等：已存在直接返回，不重复入库")
    void generateReportIdempotent() {
        TradeOrder o = closed(101L, 7L, "t", "GOLD", "BUY", "1.00", "0.00",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        TradeReport existing = new TradeReport();
        existing.setId(999L);
        when(tradeOrderMapper.getById(101L)).thenReturn(o);
        when(tradeReportMapper.findByOrderId(101L)).thenReturn(existing);

        TradeReport r = service.generateReport(101L);

        assertEquals(999L, r.getId());
        verify(tradeReportMapper, never()).insert(any());
    }

    @Test
    @DisplayName("未平仓订单拒绝生成报告")
    void rejectUnclosedOrder() {
        TradeOrder o = closed(101L, 7L, "t", "GOLD", "BUY", "1.00", "0.00",
                LocalDateTime.now(), null);
        o.setStatus("FILLED");
        when(tradeOrderMapper.getById(101L)).thenReturn(o);

        assertThrows(BizException.class, () -> service.generateReport(101L));
        verify(tradeReportMapper, never()).insert(any());
    }

    @Test
    @DisplayName("LOSS 判定：pnl<0 时为 LOSS")
    void lossResult() {
        TradeOrder o = closed(102L, 7L, "t", "GOLD", "SELL", "-2.00", "-0.50",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now());
        when(tradeOrderMapper.getById(102L)).thenReturn(o);
        when(decisionLogMapper.listByTraceId("t")).thenReturn(List.of());
        when(tradeReportMapper.insert(any())).thenReturn(1);

        TradeReport r = service.generateReport(102L);

        assertEquals("LOSS", r.getResult());
        assertEquals(0, new BigDecimal("-2.50").compareTo(r.getPnl()));
    }

    // ==================== 周期统计 ====================

    @Test
    @DisplayName("周期统计：胜率/盈亏比/净盈亏/期望/最大回撤/连亏")
    void statsComputes() {
        when(tradeOrderMapper.listClosedBetween(eq("GOLD"), any(), any())).thenReturn(List.of(
                closed(1L, 1L, "a", "GOLD", "BUY", "10", "0", LocalDateTime.now().minusMinutes(5), LocalDateTime.now()),
                closed(2L, 1L, "b", "GOLD", "BUY", "-5", "0", LocalDateTime.now().minusMinutes(5), LocalDateTime.now()),
                closed(3L, 1L, "c", "GOLD", "BUY", "7", "0", LocalDateTime.now().minusMinutes(5), LocalDateTime.now())));

        PeriodStats s = service.stats("GOLD", LocalDateTime.now().minusDays(1), null);

        assertEquals(3, s.tradeCount());
        assertEquals(2, s.winCount());
        assertEquals(1, s.lossCount());
        assertEquals(66.6667, s.winRatePct(), 0.001);
        assertEquals(17.0, s.grossProfit(), 0.001);
        assertEquals(5.0, s.grossLoss(), 0.001);
        assertEquals(3.4, s.profitFactor(), 0.001);
        assertEquals(12.0, s.netProfit(), 0.001);
        assertEquals(4.0, s.expectancy(), 0.001);
        assertEquals(5.0, s.maxDrawdown(), 0.001);
        assertEquals(1, s.maxConsecutiveLoss());
    }

    // ==================== 败因分类 ====================

    @Test
    @DisplayName("败因分类：早平/晚平/系统错误/逆势/无信号错过")
    void classifyLosses() {
        TradeOrder earlyWin = closed(1L, 1L, "a", "GOLD", "BUY", "1.0", "0",
                base().minusSeconds(60), base()); // 盈利 + 持仓 60s < 120s → 早平
        TradeOrder lateLoss = closed(2L, 1L, "b", "GOLD", "BUY", "-1.0", "0",
                base().minusSeconds(4000), base()); // 亏损 + 持仓 4000s ≥ 3600s → 晚平
        TradeOrder counterLoss = closed(3L, 1L, "c", "GOLD", "BUY", "-2.0", "0",
                base().minusSeconds(200), base()); // 亏损 + 未达晚平 → 逆势
        TradeOrder sysErr = closed(4L, 1L, "d", "GOLD", "BUY", "0.5", "0",
                base().minusSeconds(300), base());
        sysErr.setRetcode(1); // 系统错误

        when(tradeOrderMapper.listClosedBetween(eq("GOLD"), any(), any()))
                .thenReturn(List.of(earlyWin, lateLoss, counterLoss, sysErr));
        when(decisionLogMapper.countByActionBetween(eq("WAIT"), any(), any())).thenReturn(4);

        LossBreakdown b = service.classifyLosses("GOLD", LocalDateTime.now().minusDays(1), null);

        assertEquals(4, b.closedCount());
        assertEquals(2, b.lossCount());
        assertEquals(1, b.earlyClose());
        assertEquals(1, b.lateClose());
        assertEquals(1, b.systemError());
        assertEquals(1, b.counterTrendLoss());
        assertEquals(4, b.missedSignalWaits());
        assertFalse(b.notes().isEmpty());
    }

    // ==================== 策略评价 ====================

    @Test
    @DisplayName("策略评价：样本充分且达标 → 建议维持；不佳 → 建议切换")
    void evaluateStrategiesAdvice() {
        props.setMinSampleForVerdict(1);
        Strategy s = new Strategy();
        s.setId(13L);
        s.setName("趋势-EMA_ADX");
        s.setStatus("ACTIVE");
        when(strategyMapper.listAll()).thenReturn(List.of(s));
        when(tradeOrderMapper.listClosedByStrategy(eq(13L), any(), any())).thenReturn(List.of(
                closed(1L, 1L, "a", "GOLD", "BUY", "10", "0", base().minusMinutes(5), base()),
                closed(2L, 1L, "b", "GOLD", "BUY", "7", "0", base().minusMinutes(5), base()),
                closed(3L, 1L, "c", "GOLD", "BUY", "-5", "0", base().minusMinutes(5), base())));

        List<StrategyEvaluation> out = service.evaluateStrategies(null, LocalDateTime.now().minusDays(1), null);

        assertEquals(1, out.size());
        StrategyEvaluation e = out.get(0);
        assertEquals(13L, e.strategyId());
        assertEquals(3, e.tradeCount());
        assertEquals("样本充分", e.verdict());
        assertTrue(e.advice().contains("建议维持"));
    }

    @Test
    @DisplayName("策略评价：样本不足时提示、不建议据此刻切换")
    void evaluateStrategiesInsufficientSample() {
        Strategy s = new Strategy();
        s.setId(14L);
        s.setName("震荡-RSI_BOLL");
        s.setStatus("ACTIVE");
        when(strategyMapper.getById(14L)).thenReturn(s);
        when(tradeOrderMapper.listClosedByStrategy(eq(14L), any(), any())).thenReturn(List.of(
                closed(1L, 1L, "a", "GOLD", "BUY", "1", "0", base().minusMinutes(5), base())));

        List<StrategyEvaluation> out = service.evaluateStrategies(14L, LocalDateTime.now().minusDays(1), null);

        assertEquals("样本不足（1/30）", out.get(0).verdict());
        assertTrue(out.get(0).advice().contains("暂不据此切换策略"));
    }

    // ==================== 成本拆解 ====================

    @Test
    @DisplayName("成本拆解：点差/滑点/swap 分离，净利为正不判被吃")
    void analyzeCostBreakdown() {
        when(backtestRunMapper.getById(1L)).thenReturn(run(1L, 30, 2, true));
        when(backtestTradeMapper.listByRunId(1L)).thenReturn(List.of(
                trade("5.0", "-0.5"), trade("5.0", "-0.5")));

        CostAnalysis c = service.analyzeCost(1L);

        assertEquals(2, c.tradeCount());
        assertEquals(1.2, c.spreadCost(), 0.0001);
        assertEquals(0.08, c.slippageCost(), 0.0001);
        assertEquals(1.0, c.swapCost(), 0.0001);
        assertEquals(9.0, c.netProfit(), 0.0001);   // 10 - 1 (swap)
        assertEquals(11.28, c.grossProfit(), 0.0001); // 10 + 1.2 + 0.08
        assertEquals(2.28, c.totalCost(), 0.0001);
        assertFalse(c.eatenByCost());
        assertNotNull(c.spreadPct());
        assertTrue(c.verdict().contains("净利为正"));
    }

    @Test
    @DisplayName("成本拆解：毛利为正但被成本吃掉 → eatenByCost=true")
    void analyzeCostEaten() {
        when(backtestRunMapper.getById(2L)).thenReturn(run(2L, 50, 0, false));
        when(backtestTradeMapper.listByRunId(2L)).thenReturn(List.of(trade("-0.5", "0")));

        CostAnalysis c = service.analyzeCost(2L);

        assertEquals(1.0, c.spreadCost(), 0.0001);
        assertEquals(0.5, c.grossProfit(), 0.0001); // -0.5 + 1.0
        assertEquals(-0.5, c.netProfit(), 0.0001);
        assertTrue(c.eatenByCost());
        assertTrue(c.verdict().contains("被点差吃掉利润"));
    }

    @Test
    @DisplayName("成本拆解：回测不存在 → 404 业务异常")
    void analyzeCostNotFound() {
        when(backtestRunMapper.getById(9L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.analyzeCost(9L));
    }

    // ==================== 辅助 ====================

    private static LocalDateTime base() {
        return LocalDateTime.of(2026, 10, 12, 16, 0);
    }

    private static TradeOrder closed(Long id, Long sessionId, String traceId, String symbol, String cmd,
                                     String profit, String swap, LocalDateTime openTime, LocalDateTime closeTime) {
        TradeOrder o = new TradeOrder();
        o.setId(id);
        o.setSessionId(sessionId);
        o.setTraceId(traceId);
        o.setSymbol(symbol);
        o.setCmd(cmd);
        o.setVolume(new BigDecimal("0.01"));
        o.setPrice(new BigDecimal("4200.00"));
        o.setClosePrice(new BigDecimal("4201.00"));
        o.setStatus("CLOSED");
        o.setRetcode(0);
        o.setProfit(new BigDecimal(profit));
        o.setSwap(new BigDecimal(swap));
        o.setOpenTime(openTime);
        o.setCloseTime(closeTime);
        return o;
    }

    private static BacktestRun run(Long id, int spread, int slippage, boolean swapOn) {
        BacktestRun r = new BacktestRun();
        r.setId(id);
        r.setStrategyId(13L);
        r.setSymbol("GOLD");
        r.setDataTimeframe("M1");
        r.setSpread(BigDecimal.valueOf(spread));
        r.setSlippage(BigDecimal.valueOf(slippage));
        r.setSwapOn(swapOn);
        return r;
    }

    private static BacktestTrade trade(String profit, String swap) {
        BacktestTrade t = new BacktestTrade();
        t.setVolume(new BigDecimal("0.01"));
        t.setProfit(new BigDecimal(profit));
        t.setSwap(new BigDecimal(swap));
        return t;
    }
}
