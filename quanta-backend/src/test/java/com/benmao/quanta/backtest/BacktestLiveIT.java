package com.benmao.quanta.backtest;

import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 真环境端到端验收：从 MT5 桥接回填真实 GOLD M1 → 库内重放回测（单档 + 多档）。
 *
 * <p>默认不执行；需真环境（mt5_service 已起 + 真库）：
 * {@code $env:RUN_MT5_IT='true'; mvn -pl quanta-backend -am test -Dtest=BacktestLiveIT "-Dsurefire.failIfNoSpecifiedTests=true"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_MT5_IT", matches = "true")
class BacktestLiveIT {

    @Autowired
    private MarketHistoryService history;

    @Autowired
    private BacktestService backtest;

    @Autowired
    private BacktestRunMapper runMapper;

    @Autowired
    private BacktestTradeMapper tradeMapper;

    @Test
    @DisplayName("真实 GOLD M1 回填 → 事件驱动回测（单档 + 多档）")
    void backfillThenBacktestRealGoldM1() {
        BackfillResult fill = history.backfill("GOLD", "M1", 5);

        assertEquals("GOLD", fill.symbol());
        assertEquals("M1", fill.timeframe());
        assertTrue(fill.fetched() > 0, "应从 MT5 桥接拉到 K 线");
        assertTrue(fill.stored() > 0, "回填窗口内应有 M1 数据落库");
        assertNotNull(fill.cutoff());
        assertNotNull(fill.now());

        String from = fill.cutoff().toString();
        String to = fill.now().plusDays(1).toString();
        List<Long> runIds = new ArrayList<>();
        try {
            BacktestReport report = backtest.run(
                    new BacktestService.RunSpec("GOLD", "M1", from, to, 16, 0, null));
            runIds.add(report.runId());

            assertNotNull(report.runId());
            assertTrue(report.barCount() > 0, "回测应读到真实 M1 K 线");
            assertNotNull(report.strategyKey());
            assertFalse(report.strategyName() == null || report.strategyName().isBlank());
            assertNotNull(report.disclaimer());
            assertFalse(report.disclaimer().isBlank(), "报告必须显式标注成本为固定近似");
            assertEquals(report.tradeCount(), tradeMapper.listByRunId(report.runId()).size(),
                    "落库明细数应与统计一致");

            TierScanResult scan = backtest.runTiers(new BacktestService.TierSpec(
                    "GOLD", "M1", from, to, null, List.of(0, 30), List.of(0)));
            assertEquals(2, scan.tiers().size());
            scan.tiers().forEach(t -> runIds.add(t.runId()));
            assertNotNull(scan.verdict());
            assertFalse(scan.verdict().isBlank(), "应给出稳健性判据");
            assertTrue(scan.barCount() > 0);
        } finally {
            for (Long runId : runIds) {
                tradeMapper.listByRunId(runId).forEach(t -> tradeMapper.deleteById(t.getId()));
                runMapper.deleteById(runId);
            }
        }
    }
}
