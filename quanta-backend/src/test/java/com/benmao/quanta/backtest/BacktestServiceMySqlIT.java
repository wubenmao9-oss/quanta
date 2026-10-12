package com.benmao.quanta.backtest;

import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.MarketKline;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 回测层真库集成测试：真实落 {@code market_kline} → 跑回测 → 落 {@code backtest_run}/{@code backtest_trade}
 * → 同参数复跑结果<b>完全一致</b>（可复现）。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=BacktestServiceMySqlIT "-Dsurefire.failIfNoSpecifiedTests=true"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class BacktestServiceMySqlIT {

    @Autowired
    private BacktestProperties props;

    @Autowired
    private MarketKlineMapper klineMapper;

    @Autowired
    private BacktestRunMapper runMapper;

    @Autowired
    private BacktestTradeMapper tradeMapper;

    @Autowired
    private StrategyMapper strategyMapper;

    /** 用固定时钟构造服务，保证 from/to 与库内 bar_time 口径一致。 */
    private BacktestService service() {
        return new BacktestService(props, klineMapper, runMapper, tradeMapper, strategyMapper,
                List.of(new CycleStrategy()), Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("单档回测：落库 + 同参数复跑结果完全一致（可复现）")
    void runPersistsAndIsReproducible() {
        String symbol = "IT-M8-" + System.currentTimeMillis();
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 0, 0);
        seedBars(symbol, base, 400);

        List<Long> runIds = new ArrayList<>();
        try {
            BacktestService svc = service();
            String from = base.toString();
            String to = base.plusMinutes(400).toString();
            BacktestService.RunSpec spec =
                    new BacktestService.RunSpec(symbol, "M1", from, to, 16, 0, CycleStrategy.KEY);

            BacktestReport r1 = svc.run(spec);
            runIds.add(r1.runId());

            // 落库核对：backtest_run 一行，RUN/RULE 元信息正确
            assertNotNull(r1.runId(), "应回填 backtest_run.id");
            BacktestRun row = runMapper.getById(r1.runId());
            assertNotNull(row, "backtest_run 应已落库");
            assertEquals(symbol, row.getSymbol());
            assertEquals("M1", row.getDataTimeframe());
            assertEquals("RULE", row.getMode(), "M8 为 RULE 模式，不调 LLM");
            assertEquals("DONE", row.getStatus());
            assertEquals("FIXED", row.getSpreadMode());
            assertEquals(0, row.getSpread().compareTo(new java.math.BigDecimal("16")),
                    "点差应按入参落库");
            assertEquals(props.isSwapOn(), row.getSwapOn());
            assertNotNull(row.getStrategyId(), "strategy_id 不应为空（内置规则策略行）");

            // 有成交，且明细行数与统计一致
            assertTrue(r1.tradeCount() > 0, "构造的行情应触发成交，实际=" + r1.tradeCount());
            List<BacktestTrade> trades = tradeMapper.listByRunId(r1.runId());
            assertEquals(r1.tradeCount(), trades.size(), "backtest_trade 明细数应与统计一致");

            // 显式免责声明（点差/swap 为固定近似）
            assertNotNull(r1.disclaimer());
            assertFalse(r1.disclaimer().isBlank(), "报告必须显式标注成本为固定近似");
            assertEquals(props.getDisclaimer(), r1.disclaimer());

            // 同参数复跑：结果必须完全一致
            BacktestReport r2 = svc.run(spec);
            runIds.add(r2.runId());
            assertEquals(r1.tradeCount(), r2.tradeCount(), "同参数交易笔数应一致");
            assertEquals(r1.winRatePct(), r2.winRatePct(), 1e-9, "胜率应一致");
            assertEquals(r1.profitFactor(), r2.profitFactor(), 1e-9, "盈亏比应一致");
            assertEquals(r1.maxDrawdown(), r2.maxDrawdown(), 1e-9, "最大回撤应一致");
            assertEquals(r1.netProfit(), r2.netProfit(), 1e-9, "净利应一致");
            assertEquals(r1.finalEquity(), r2.finalEquity(), 1e-9, "终值权益应一致");

            // getRun 能从库重建报告（含明细）
            BacktestReport loaded = svc.getRun(r1.runId());
            assertEquals(r1.tradeCount(), loaded.tradeCount());
            assertEquals(r1.tradeCount(), loaded.trades().size());
        } finally {
            cleanup(symbol, base, 400, runIds);
        }
    }

    @Test
    @DisplayName("多档扫描：默认档位汇总 + 稳健性判据 + 免责声明")
    void tiersScanProducesDefaultTiers() {
        String symbol = "IT-M8T-" + System.currentTimeMillis();
        LocalDateTime base = LocalDateTime.of(2026, 9, 2, 0, 0);
        seedBars(symbol, base, 400);

        List<Long> runIds = new ArrayList<>();
        try {
            BacktestService svc = service();
            BacktestService.TierSpec spec = new BacktestService.TierSpec(
                    symbol, "M1", base.toString(), base.plusMinutes(400).toString(),
                    CycleStrategy.KEY, null, null);

            TierScanResult result = svc.runTiers(spec);

            assertEquals(props.getSpreadTiers().size(), result.tiers().size(),
                    "默认档位应为配置中的点差档数（滑点取单一默认值）");
            assertTrue(result.barCount() > 0, "扫描应读到 K 线");
            assertNotNull(result.verdict());
            assertFalse(result.verdict().isBlank(), "应给出稳健性判据");
            assertEquals(props.getDisclaimer(), result.disclaimer());
            for (TierScanResult.TierSummary t : result.tiers()) {
                assertNotNull(t.runId(), "每档应各自落一条 backtest_run");
                runIds.add(t.runId());
            }
            // 点差越大，净利不应更高（成本单调不增）
            TierScanResult.TierSummary low = result.tiers().get(0);
            TierScanResult.TierSummary high = result.tiers().get(result.tiers().size() - 1);
            assertTrue(high.netProfit() <= low.netProfit() + 1e-9,
                    "高成本档净利不应高于低成本档：low=" + low.netProfit() + ", high=" + high.netProfit());
        } finally {
            cleanup(symbol, base, 400, runIds);
        }
    }

    // ==================== 测试夹具 ====================

    /** 生成三角波 M1 行情（固定、确定），保证既触发 TP 也触发 SL。 */
    private void seedBars(String symbol, LocalDateTime base, int count) {
        double basePrice = 2000.0;
        double amp = 5.0;
        int period = 40;
        double prev = basePrice;
        for (int i = 0; i < count; i++) {
            double phase = (i % period) / (double) period;
            double tri = phase < 0.5 ? phase * 2 : 2 - phase * 2;
            double close = basePrice + tri * amp;
            double open = prev;
            double high = Math.max(open, close) + 0.5;
            double low = Math.min(open, close) - 0.5;
            klineMapper.insertIgnore(symbol, "M1", base.plusMinutes(i), open, high, low, close, 10 + (i % 7));
            prev = close;
        }
    }

    private void cleanup(String symbol, LocalDateTime base, int count, List<Long> runIds) {
        for (Long runId : runIds) {
            for (BacktestTrade t : tradeMapper.listByRunId(runId)) {
                tradeMapper.deleteById(t.getId());
            }
            runMapper.deleteById(runId);
        }
        klineMapper.listRange(symbol, "M1", base, base.plusMinutes(count + 1))
                .stream().map(MarketKline::getId).forEach(klineMapper::deleteById);
        Strategy builtIn = strategyMapper.findByNameVersion("内置规则-" + CycleStrategy.KEY, 1);
        if (builtIn != null) {
            strategyMapper.deleteById(builtIn.getId());
        }
    }

    /** 固定周期规则（每 20 根开一次多单，SL=2.0 / TP=2.0），保证确定性的成交与盈亏分布。 */
    static final class CycleStrategy implements RuleStrategy {

        static final String KEY = "CYCLE_IT";

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public String displayName() {
            return "IT 固定周期开仓（回测落库/复现验证）";
        }

        @Override
        public RuleRunner newRunner() {
            return new CycleRunner();
        }
    }

    static final class CycleRunner implements RuleRunner {

        @Override
        public int warmupBars() {
            return 1;
        }

        @Override
        public EntrySignal onBar(ReplayView view, OpenPosition position) {
            if (position != null) {
                return null;
            }
            return view.cursor() % 20 == 0 ? new EntrySignal(RiskSide.BUY, 2.0, 1.0) : null;
        }
    }
}
