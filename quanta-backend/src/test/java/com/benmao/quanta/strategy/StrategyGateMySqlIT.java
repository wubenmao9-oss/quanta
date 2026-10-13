package com.benmao.quanta.strategy;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.MarketKline;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.StrategyGateReport.Criterion;
import com.benmao.quanta.strategy.StrategyGateReport.Segment;
import com.benmao.quanta.strategy.rule.RangeRsiBollRuleStrategy;
import com.benmao.quanta.strategy.rule.TrendEmaAdxRuleStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 策略门禁真库集成测试：真实落 {@code market_kline} → 幂等导入首批两策略（DRAFT）
 * → 跑「全样本/样本内/样本外」三段回测 → 门禁回执 → <b>未经门禁通过禁止 approve 置 ACTIVE</b>。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=StrategyGateMySqlIT "-Dsurefire.failIfNoSpecifiedTests=true"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class StrategyGateMySqlIT {

    @Autowired
    private StrategyGateService gateService;

    @Autowired
    private StrategyCatalogService catalog;

    @Autowired
    private StrategyProperties props;

    @Autowired
    private StrategyMapper strategyMapper;

    @Autowired
    private MarketKlineMapper klineMapper;

    @Autowired
    private BacktestRunMapper runMapper;

    @Autowired
    private BacktestTradeMapper tradeMapper;

    @Test
    @DisplayName("幂等导入首批两策略（趋势+震荡）为 DRAFT：字段齐备、(name,version) 不重复")
    void importsBothStrategiesAsDraftIdempotently() {
        try {
            List<Strategy> first = catalog.ensureImported();
            List<Strategy> second = catalog.ensureImported();

            assertEquals(2, first.size(), "应导入趋势+震荡两份策略");
            assertEquals(2, second.size(), "再次导入应仍为两份");
            assertEquals(ids(first), ids(second), "幂等：二次导入不应新增行，id 应稳定");

            Strategy trend = strategyMapper.findByNameVersion(StrategyCatalogService.NAME_TREND, 1);
            Strategy range = strategyMapper.findByNameVersion(StrategyCatalogService.NAME_RANGE, 1);
            assertNotNull(trend, "趋势策略应已落库");
            assertNotNull(range, "震荡策略应已落库");

            assertEquals("DRAFT", trend.getStatus(), "初始状态必须为 DRAFT（未经批准不得上线）");
            assertFalse(trend.getApproved(), "初始 approved 必须为 false");
            assertEquals("TREND", trend.getRegimeType());
            assertTrue(trend.getTargetRr().doubleValue() > 0, "target_rr 应为正");
            assertTrue(trend.getPrompt().contains("趋势"), "Prompt 应含趋势语义");
            assertTrue(trend.getIndicatorParams().contains(TrendEmaAdxRuleStrategy.KEY),
                    "indicator_params 应含规则键");

            assertEquals("DRAFT", range.getStatus());
            assertEquals("RANGE", range.getRegimeType());
            assertTrue(range.getTargetRr().doubleValue() > 0);
            assertTrue(range.getIndicatorParams().contains(RangeRsiBollRuleStrategy.KEY));

            assertEquals(1, countByName(StrategyCatalogService.NAME_TREND), "唯一键应防止重复");
            assertEquals(1, countByName(StrategyCatalogService.NAME_RANGE), "唯一键应防止重复");
        } finally {
            deleteCatalogRows();
        }
    }

    @Test
    @DisplayName("门禁报告结构：5 条判据 + 样本内外时间切线 + 三段回测落库 + 免责声明 + 不改状态")
    void gateProducesStructuredReport() {
        String symbol = "IT-M9-" + System.currentTimeMillis();
        LocalDateTime base = LocalDateTime.of(2026, 8, 1, 0, 0);
        int count = 600;
        seedBars(symbol, base, count);
        List<Long> runIds = new ArrayList<>();
        try {
            List<Strategy> imported = catalog.ensureImported();
            Strategy trend = find(imported, StrategyCatalogService.NAME_TREND);

            // from/to 传空 → 走库内 min/max bar_time（顺带覆盖新增的 MarketKlineMapper 方法）
            StrategyGateReport r = gateService.gate(trend.getId(), symbol, null, null);

            assertEquals(List.of("SAMPLE_SIZE", "SPREAD_EXPECTANCY", "OOS_POSITIVE", "MAX_DRAWDOWN", "WINRATE_RR"),
                    r.criteria().stream().map(Criterion::id).toList(), "判据顺序应与 P0清单 M9 一致");
            assertEquals(props.getGate().getSpreadPoints(), r.spreadPoints(), "门禁点差档应取自集中配置");
            assertEquals(TrendEmaAdxRuleStrategy.KEY, r.ruleKey());
            assertEquals("TREND", r.regimeType());

            assertTrue(r.from().isBefore(r.splitPoint()), "样本内起点应早于切线");
            assertTrue(r.splitPoint().isBefore(r.to()), "切线应早于样本外终点");

            for (Segment seg : List.of(r.full(), r.inSample(), r.outOfSample())) {
                assertNotNull(seg.runId(), "每段都应落一条 backtest_run");
                assertNotNull(runMapper.getById(seg.runId()), "backtest_run 应已落库");
                assertEquals(seg.tradeCount(), tradeMapper.listByRunId(seg.runId()).size(),
                        "明细行数应与统计一致");
                runIds.add(seg.runId());
            }

            assertEquals(r.criteria().stream().allMatch(Criterion::pass), r.passed(),
                    "passed 应为全部判据的合取");
            assertFalse(r.verdict().isBlank());
            assertFalse(r.abConclusion().isBlank(), "应给出样本内→样本外对比结论");
            assertFalse(r.disclaimer().isBlank(), "报告必须显式标注成本为固定近似");

            assertEquals("DRAFT", strategyMapper.getById(trend.getId()).getStatus(),
                    "门禁评估本身不得改变策略状态（仅 approve 可置 ACTIVE）");
        } finally {
            cleanupRuns(runIds);
            cleanupBars(symbol, base, count);
            deleteCatalogRows();
        }
    }

    @Test
    @DisplayName("approve 门禁契约：未通过 → 拒绝置 ACTIVE 并回执失败项；通过 → 置 ACTIVE 且 approved=true")
    void approveEnforcesGate() {
        String symbol = "IT-M9A-" + System.currentTimeMillis();
        LocalDateTime base = LocalDateTime.of(2026, 8, 2, 0, 0);
        int count = 600;
        seedBars(symbol, base, count);
        List<Long> runIds = new ArrayList<>();
        try {
            List<Strategy> imported = catalog.ensureImported();
            Strategy trend = find(imported, StrategyCatalogService.NAME_TREND);

            StrategyGateReport r = gateService.gate(trend.getId(), symbol, null, null);
            collectRunIds(r, runIds);

            assertEquals("DRAFT", strategyMapper.getById(trend.getId()).getStatus(),
                    "门禁评估后仍应为 DRAFT");

            if (r.passed()) {
                Strategy after = gateService.approve(trend.getId(), symbol, null, null);
                assertEquals("ACTIVE", after.getStatus());
                assertTrue(after.getApproved());
                // 幂等：再次批准仍为 ACTIVE
                assertEquals("ACTIVE", gateService.approve(trend.getId(), symbol, null, null).getStatus());
            } else {
                BizException ex = assertThrows(BizException.class,
                        () -> gateService.approve(trend.getId(), symbol, null, null));
                assertTrue(ex.getMessage().contains("门禁未通过"), "应回执门禁失败项：" + ex.getMessage());
                assertEquals("DRAFT", strategyMapper.getById(trend.getId()).getStatus(),
                        "门禁未通过时状态不得改变");
            }
        } finally {
            cleanupRuns(runIds);
            cleanupBars(symbol, base, count);
            deleteCatalogRows();
        }
    }

    @Test
    @DisplayName("门禁未达标（样本量不足）→ 判据不过、verdict 回执失败项、禁止 approve")
    void gateFailsWhenSampleSizeInsufficient() {
        String symbol = "IT-M9F-" + System.currentTimeMillis();
        LocalDateTime base = LocalDateTime.of(2026, 8, 3, 0, 0);
        int count = 600;
        seedBars(symbol, base, count);
        List<Long> runIds = new ArrayList<>();
        int originalMin = props.getGate().getMinTradeCount();
        try {
            // 把最小样本量阈值抬到不可能达到（确定性制造门禁不过）
            props.getGate().setMinTradeCount(Integer.MAX_VALUE);

            List<Strategy> imported = catalog.ensureImported();
            Strategy range = find(imported, StrategyCatalogService.NAME_RANGE);

            StrategyGateReport r = gateService.gate(range.getId(), symbol, null, null);
            collectRunIds(r, runIds);

            assertFalse(r.passed(), "样本量不足时门禁必须不过");
            Criterion c1 = r.criteria().get(0);
            assertEquals("SAMPLE_SIZE", c1.id());
            assertFalse(c1.pass());
            assertTrue(r.verdict().contains("最小交易样本量"), "verdict 应回执失败项：" + r.verdict());

            assertThrows(BizException.class, () -> gateService.approve(range.getId(), symbol, null, null));
            assertEquals("DRAFT", strategyMapper.getById(range.getId()).getStatus(),
                    "门禁未过时不得置 ACTIVE");
        } finally {
            props.getGate().setMinTradeCount(originalMin);
            cleanupRuns(runIds);
            cleanupBars(symbol, base, count);
            deleteCatalogRows();
        }
    }

    // ==================== 测试夹具 ====================

    /** 生成确定性的"震荡+趋势"混合 M1 行情（三角波叠加缓慢漂移），保证既有触轨也有均线穿越。 */
    private void seedBars(String symbol, LocalDateTime base, int count) {
        double basePrice = 2000.0;
        double amp = 5.0;
        int period = 40;
        double prev = basePrice;
        for (int i = 0; i < count; i++) {
            double phase = (i % period) / (double) period;
            double tri = phase < 0.5 ? phase * 2 : 2 - phase * 2;
            double drift = (i / (double) count) * 20.0; // 缓慢上行，制造趋势段
            double close = basePrice + tri * amp + drift;
            double open = prev;
            double high = Math.max(open, close) + 0.5;
            double low = Math.min(open, close) - 0.5;
            klineMapper.insertIgnore(symbol, "M1", base.plusMinutes(i), open, high, low, close, 10 + (i % 7));
            prev = close;
        }
    }

    private static List<Long> ids(List<Strategy> strategies) {
        List<Long> out = new ArrayList<>();
        for (Strategy s : strategies) {
            out.add(s.getId());
        }
        return out;
    }

    private static Strategy find(List<Strategy> strategies, String name) {
        return strategies.stream().filter(s -> name.equals(s.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("未找到策略：" + name));
    }

    private long countByName(String name) {
        return strategyMapper.listAll().stream().filter(s -> name.equals(s.getName())).count();
    }

    private static void collectRunIds(StrategyGateReport r, List<Long> runIds) {
        for (Segment seg : List.of(r.full(), r.inSample(), r.outOfSample())) {
            if (seg.runId() != null) {
                runIds.add(seg.runId());
            }
        }
    }

    private void cleanupRuns(List<Long> runIds) {
        for (Long runId : runIds) {
            for (BacktestTrade t : tradeMapper.listByRunId(runId)) {
                tradeMapper.deleteById(t.getId());
            }
            runMapper.deleteById(runId);
        }
    }

    private void cleanupBars(String symbol, LocalDateTime base, int count) {
        klineMapper.listRange(symbol, "M1", base, base.plusMinutes(count + 1))
                .stream().map(MarketKline::getId).forEach(klineMapper::deleteById);
    }

    private void deleteCatalogRows() {
        for (String name : List.of(StrategyCatalogService.NAME_TREND, StrategyCatalogService.NAME_RANGE)) {
            Strategy s = strategyMapper.findByNameVersion(name, 1);
            if (s != null) {
                strategyMapper.deleteById(s.getId());
            }
        }
    }
}
