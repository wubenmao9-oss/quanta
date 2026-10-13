package com.benmao.quanta.backtest;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.BacktestRun;
import com.benmao.quanta.entity.BacktestTrade;
import com.benmao.quanta.entity.MarketKline;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.BacktestRunMapper;
import com.benmao.quanta.mapper.BacktestTradeMapper;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 回测服务（M8）：加载历史 K 线 → 事件驱动重放（RULE 模式）→ 统计 → 落库 → 报告。
 *
 * <p>要点（对应设计 §13）：
 * <ul>
 *   <li><b>RULE 模式不调 LLM</b>：仅用规则策略，快且免费，用于"先看历史胜率"；</li>
 *   <li><b>可复现</b>：重放引擎全程确定性，同参数两次结果完全一致；</li>
 *   <li><b>点差多档扫描</b>：每个点差/滑点档位落一条 {@code backtest_run}，汇总为对照表；</li>
 *   <li><b>防幻觉</b>：周期白名单、点差/滑点上下界、时间区间校验，非法一律拒绝并回执合法范围。</li>
 * </ul>
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    /** profitFactor 无亏损单时的封顶值（DECIMAL(8,2) 无法存 Infinity）。 */
    private static final double PROFIT_FACTOR_CAP = 9999.99;

    /** 参数快照序列化（线程安全，只读使用）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BacktestProperties props;
    private final MarketKlineMapper klineMapper;
    private final BacktestRunMapper runMapper;
    private final BacktestTradeMapper tradeMapper;
    private final StrategyMapper strategyMapper;
    private final Map<String, RuleStrategy> strategies;
    private final Clock clock;

    @Autowired
    public BacktestService(BacktestProperties props,
                           MarketKlineMapper klineMapper,
                           BacktestRunMapper runMapper,
                           BacktestTradeMapper tradeMapper,
                           StrategyMapper strategyMapper,
                           List<RuleStrategy> strategies) {
        this(props, klineMapper, runMapper, tradeMapper, strategyMapper, strategies, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    BacktestService(BacktestProperties props,
                    MarketKlineMapper klineMapper,
                    BacktestRunMapper runMapper,
                    BacktestTradeMapper tradeMapper,
                    StrategyMapper strategyMapper,
                    List<RuleStrategy> strategies,
                    Clock clock) {
        this.props = props;
        this.klineMapper = klineMapper;
        this.runMapper = runMapper;
        this.tradeMapper = tradeMapper;
        this.strategyMapper = strategyMapper;
        Map<String, RuleStrategy> map = new LinkedHashMap<>();
        for (RuleStrategy s : strategies) {
            map.put(s.key(), s);
        }
        this.strategies = map;
        this.clock = clock;
    }

    /** 可用规则策略清单。 */
    public List<StrategyView> availableStrategies() {
        List<StrategyView> out = new ArrayList<>();
        for (RuleStrategy s : strategies.values()) {
            out.add(new StrategyView(s.key(), s.displayName()));
        }
        return out;
    }

    /** 单档回测。 */
    public BacktestReport run(RunSpec spec) {
        Resolved r = resolve(spec.symbol(), spec.timeframe(), spec.from(), spec.to());
        RuleStrategy strategy = strategyByKey(spec.strategyKey());
        Long strategyId = resolveStrategyId(spec.strategyId());
        int spread = spec.spreadPoints() == null ? 0 : spec.spreadPoints();
        int slippage = spec.slippagePoints() == null ? props.getDefaultSlippage() : spec.slippagePoints();
        validateSpread(spread);
        validateSlippage(slippage);
        return execute(r, strategy, spread, slippage, strategyId);
    }

    /** 点差/滑点多档扫描（设计 §13.4）。 */
    public TierScanResult runTiers(TierSpec spec) {
        Resolved r = resolve(spec.symbol(), spec.timeframe(), spec.from(), spec.to());
        RuleStrategy strategy = strategyByKey(spec.strategyKey());
        Long strategyId = resolveStrategyId(spec.strategyId());

        List<Integer> spreads = (spec.spreads() == null || spec.spreads().isEmpty())
                ? props.getSpreadTiers() : spec.spreads();
        List<Integer> slippages = (spec.slippages() == null || spec.slippages().isEmpty())
                ? List.of(props.getDefaultSlippage()) : spec.slippages();
        for (Integer s : spreads) {
            validateSpread(s == null ? 0 : s);
        }
        for (Integer s : slippages) {
            validateSlippage(s == null ? 0 : s);
        }

        List<TierScanResult.TierSummary> tiers = new ArrayList<>();
        int barCount = 0;
        for (Integer spread : spreads) {
            for (Integer slippage : slippages) {
                BacktestReport report = execute(r, strategy, spread, slippage, strategyId);
                barCount = report.barCount();
                tiers.add(new TierScanResult.TierSummary(report.runId(), report.spreadPoints(),
                        report.slippagePoints(), report.tradeCount(), report.winRatePct(),
                        report.profitFactor(), report.maxDrawdown(), report.netProfit(), report.expectancy()));
            }
        }
        return new TierScanResult(r.symbol(), r.timeframe(), strategy.key(), r.from(), r.to(),
                barCount, tiers, verdict(tiers), props.getDisclaimer());
    }

    /** 按 id 读取回测报告（含明细）。 */
    public BacktestReport getRun(long runId) {
        BacktestRun run = runMapper.getById(runId);
        if (run == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "回测记录不存在：id=" + runId, 404);
        }
        List<ClosedTrade> trades = new ArrayList<>();
        for (BacktestTrade t : tradeMapper.listByRunId(runId)) {
            trades.add(new ClosedTrade(0, 0, t.getOpenTime(), t.getCloseTime(),
                    com.benmao.quanta.risk.RiskSide.valueOf(t.getCmd()),
                    d(t.getVolume()), d(t.getOpenPrice()), d(t.getClosePrice()),
                    d(t.getSl()), d(t.getTp()), d(t.getProfit()), d(t.getSwap()),
                    t.getHoldSec() == null ? 0 : t.getHoldSec(), "STORED"));
        }
        double net = trades.stream().mapToDouble(ClosedTrade::netProfit).sum();
        double grossProfit = trades.stream().mapToDouble(t -> Math.max(0, t.netProfit())).sum();
        double grossLoss = trades.stream().mapToDouble(t -> Math.min(0, t.netProfit())).sum();
        double expectancy = trades.isEmpty() ? 0 : net / trades.size();
        String name = run.getStrategyId() == null ? null : strategyName(run.getStrategyId());
        return new BacktestReport(run.getId(), null, name, run.getSymbol(), run.getDataTimeframe(),
                run.getStartTime(), run.getEndTime(), trades.size(), run.getSpreadMode(),
                d(run.getSpread()), d(run.getSlippage()), Boolean.TRUE.equals(run.getSwapOn()),
                run.getTradeCount() == null ? 0 : run.getTradeCount(),
                d(run.getWinRate()), d(run.getProfitFactor()), d(run.getMaxDrawdown()),
                run.getAvgHoldSec() == null ? 0 : run.getAvgHoldSec(), round2(expectancy),
                round2(grossProfit), round2(grossLoss), round2(net),
                props.getInitialEquity(), round2(props.getInitialEquity() + net),
                props.getDisclaimer(), trades);
    }

    // ==================== 内部实现 ====================

    private BacktestReport execute(Resolved r, RuleStrategy strategy, int spread, int slippage, Long strategyId) {
        List<KlineBar> bars = loadBars(r);
        ReplayEngine engine = new ReplayEngine(props, clock.getZone());
        EngineResult result = engine.run(bars, strategy.newRunner(), spread, slippage);
        Stats stats = computeStats(result);
        BacktestRun run = persistRun(r, strategy, spread, slippage, stats, strategyId);
        persistTrades(run.getId(), result.trades());
        log.info("回测完成：runId={}, strategy={}, symbol={}, timeframe={}, spread={}, slippage={}, "
                        + "bars={}, trades={}, winRate={}%, pf={}, net={}",
                run.getId(), strategy.key(), r.symbol(), r.timeframe(), spread, slippage,
                bars.size(), stats.tradeCount(), stats.winRatePct(), stats.profitFactor(), stats.netProfit());
        String name = strategyId != null ? strategyName(strategyId) : strategy.displayName();
        return new BacktestReport(run.getId(), strategy.key(), name, r.symbol(), r.timeframe(),
                r.from(), r.to(), bars.size(), run.getSpreadMode(), spread, slippage, props.isSwapOn(),
                stats.tradeCount(), stats.winRatePct(), stats.profitFactor(), stats.maxDrawdown(),
                stats.avgHoldSec(), stats.expectancy(), stats.grossProfit(), stats.grossLoss(),
                stats.netProfit(), props.getInitialEquity(), result.finalEquity(),
                props.getDisclaimer(), result.trades());
    }

    private List<KlineBar> loadBars(Resolved r) {
        List<MarketKline> rows = klineMapper.listRange(r.symbol(), r.timeframe(), r.from(), r.to());
        if (rows == null || rows.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "区间内无 K 线数据（symbol=" + r.symbol() + ", timeframe=" + r.timeframe()
                            + ", [" + r.from() + ", " + r.to() + ")）；请先回填历史：POST /api/backtest/backfill",
                    400);
        }
        List<KlineBar> bars = new ArrayList<>(rows.size());
        for (MarketKline k : rows) {
            long epoch = k.getBarTime().atZone(clock.getZone()).toEpochSecond();
            bars.add(new KlineBar(epoch, d(k.getOpen()), d(k.getHigh()), d(k.getLow()), d(k.getClose()),
                    k.getVolume() == null ? 0 : k.getVolume()));
        }
        return bars;
    }

    private Stats computeStats(EngineResult result) {
        List<ClosedTrade> trades = result.trades();
        int n = trades.size();
        double grossProfit = 0;
        double grossLoss = 0;
        double sumNet = 0;
        long sumHold = 0;
        int wins = 0;
        for (ClosedTrade t : trades) {
            double net = t.netProfit();
            sumNet += net;
            sumHold += t.holdSec();
            if (net > 0) {
                wins++;
                grossProfit += net;
            } else if (net < 0) {
                grossLoss += net;
            }
        }
        double winRate = n == 0 ? 0 : 100.0 * wins / n;
        double profitFactor;
        if (grossLoss < 0) {
            profitFactor = grossProfit / (-grossLoss);
        } else {
            profitFactor = grossProfit > 0 ? PROFIT_FACTOR_CAP : 0;
        }
        int avgHold = n == 0 ? 0 : (int) Math.round((double) sumHold / n);
        double expectancy = n == 0 ? 0 : sumNet / n;
        double net = result.finalEquity() - result.initialEquity();
        return new Stats(n, round2(winRate), round2(Math.min(profitFactor, PROFIT_FACTOR_CAP)),
                round2(result.maxDrawdown()), avgHold, expectancy,
                round2(grossProfit), round2(grossLoss), round2(net));
    }

    private BacktestRun persistRun(Resolved r, RuleStrategy strategy, int spread, int slippage, Stats stats,
                                   Long strategyId) {
        BacktestRun run = new BacktestRun();
        run.setStrategyId(strategyId != null ? strategyId : ensureBuiltInStrategy(strategy));
        run.setSymbol(r.symbol());
        run.setDataTimeframe(r.timeframe());
        run.setStartTime(r.from());
        run.setEndTime(r.to());
        run.setParams(paramsJson(strategy, spread, slippage));
        run.setSpreadMode(spread == 0 ? "OFF" : "FIXED");
        run.setSpread(BigDecimal.valueOf(spread));
        run.setSlippage(BigDecimal.valueOf(slippage));
        run.setSwapOn(props.isSwapOn());
        run.setMode("RULE");
        run.setWinRate(BigDecimal.valueOf(stats.winRatePct()));
        run.setProfitFactor(BigDecimal.valueOf(stats.profitFactor()));
        run.setMaxDrawdown(BigDecimal.valueOf(stats.maxDrawdown()));
        run.setTradeCount(stats.tradeCount());
        run.setAvgHoldSec(stats.avgHoldSec());
        run.setStatus("DONE");
        runMapper.insert(run);
        return run;
    }

    private void persistTrades(Long runId, List<ClosedTrade> trades) {
        for (ClosedTrade t : trades) {
            BacktestTrade e = new BacktestTrade();
            e.setRunId(runId);
            e.setOpenTime(t.openTime());
            e.setCloseTime(t.closeTime());
            e.setCmd(t.side().name());
            e.setVolume(BigDecimal.valueOf(t.volume()));
            e.setOpenPrice(BigDecimal.valueOf(t.openPrice()));
            e.setClosePrice(BigDecimal.valueOf(t.closePrice()));
            e.setSl(BigDecimal.valueOf(t.sl()));
            e.setTp(BigDecimal.valueOf(t.tp()));
            e.setProfit(BigDecimal.valueOf(t.profit()));
            e.setSwap(BigDecimal.valueOf(t.swap()));
            e.setHoldSec(t.holdSec());
            tradeMapper.insert(e);
        }
    }

    /** 幂等获取内置规则策略行（回测落库需要 strategy_id 非空）。 */
    private Long ensureBuiltInStrategy(RuleStrategy strategy) {
        String name = "内置规则-" + strategy.key();
        Strategy existing = strategyMapper.findByNameVersion(name, 1);
        if (existing != null) {
            return existing.getId();
        }
        Strategy s = new Strategy();
        s.setName(name);
        s.setPrompt(strategy.displayName());
        s.setVersion(1);
        s.setStatus("DRAFT");
        s.setOrigin("MANUAL");
        s.setApproved(false);
        s.setRegimeType("TREND");
        s.setTargetRr(BigDecimal.valueOf(props.getStrategy().getTargetRr()));
        s.setIndicatorParams(paramsJson(strategy, 0, props.getDefaultSlippage()));
        s.setDescription("M8 回测内置 RULE 示例规则，作为 M9 真策略（Prompt → 规则）的落地模板");
        strategyMapper.insert(s);
        return s.getId();
    }

    private String strategyName(Long strategyId) {
        Strategy s = strategyMapper.getById(strategyId);
        return s == null ? null : s.getName();
    }

    private RuleStrategy strategyByKey(String key) {
        String k = (key == null || key.isBlank()) ? com.benmao.quanta.backtest.rule.EmaAtrRuleStrategy.KEY : key.trim();
        RuleStrategy s = strategies.get(k);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的策略：" + key + "，可选：" + strategies.keySet(), 400);
        }
        return s;
    }

    private Resolved resolve(String symbol, String timeframe, String from, String to) {
        String sym = (symbol == null || symbol.isBlank()) ? props.getSymbol() : symbol.trim();
        String tf = (timeframe == null || timeframe.isBlank()) ? props.getTimeframe() : timeframe.trim().toUpperCase(Locale.ROOT);
        if (!props.getAllowedTimeframes().contains(tf)) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的周期：" + timeframe + "，可选：" + props.getAllowedTimeframes(), 400);
        }
        LocalDateTime f = parseTime(from, "from");
        LocalDateTime t = parseTime(to, "to");
        if (!f.isBefore(t)) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "时间区间非法：from(" + f + ") 必须早于 to(" + t + ")", 400);
        }
        return new Resolved(sym, tf, f, t);
    }

    private void validateSpread(int spread) {
        if (spread < 0 || spread > props.getMaxSpreadPoints()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "点差非法：" + spread + "，合法范围 [0, " + props.getMaxSpreadPoints() + "]（points）", 400);
        }
    }

    private void validateSlippage(int slippage) {
        if (slippage < 0 || slippage > props.getMaxSlippagePoints()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "滑点非法：" + slippage + "，合法范围 [0, " + props.getMaxSlippagePoints() + "]（points）", 400);
        }
    }

    private static LocalDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    field + " 不能为空，合法格式：2026-09-12T00:00:00 / 2026-09-12 00:00:00 / 2026-09-12", 400);
        }
        String s = raw.trim().replace(' ', 'T');
        if (s.length() == 10) {
            s = s + "T00:00:00";
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    field + " 解析失败：" + raw
                            + "，合法格式：2026-09-12T00:00:00 / 2026-09-12 00:00:00 / 2026-09-12", 400);
        }
    }

    private String verdict(List<TierScanResult.TierSummary> tiers) {
        TierScanResult.TierSummary high = null;
        for (TierScanResult.TierSummary t : tiers) {
            if (t.spreadPoints() >= 30 && (high == null || t.spreadPoints() < high.spreadPoints())) {
                high = t;
            }
        }
        if (high == null) {
            return "未包含高成本档（≥30 点差），无法判定稳健性";
        }
        if (high.tradeCount() == 0) {
            return "高成本档（" + fmt(high.spreadPoints()) + " 点差）无成交，样本不足，无法判定";
        }
        if (high.expectancy() > 0) {
            return "在高成本档（" + fmt(high.spreadPoints()) + " 点差）下期望仍为正 → 稳健（真 α）";
        }
        return "在高成本档（" + fmt(high.spreadPoints()) + " 点差）下期望为负 → 脆弱（或靠低成本吃饭），实盘危险";
    }

    private String paramsJson(RuleStrategy strategy, int spread, int slippage) {
        Map<String, Object> m = new LinkedHashMap<>(strategy.indicatorParams());
        m.put("strategyKey", strategy.key());
        m.put("volume", props.getVolume());
        m.put("spread", spread);
        m.put("slippage", slippage);
        m.put("spreadSideFactor", props.getSpreadSideFactor());
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR.code(), "策略参数序列化失败：" + strategy.key(), 500);
        }
    }

    /** 校验并回显入参策略 id（关联真实策略行，供 M9 门禁留痕）；为空则不关联（回测落库用内置规则行）。 */
    private Long resolveStrategyId(Long strategyId) {
        if (strategyId == null) {
            return null;
        }
        Strategy s = strategyMapper.getById(strategyId);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略不存在：id=" + strategyId, 404);
        }
        return strategyId;
    }

    private static double d(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ==================== 入参 / 中间类型 ====================

    /** 单档回测入参（from/to 为字符串，支持多种时间格式）。 */
    public record RunSpec(String symbol,
                          String timeframe,
                          String from,
                          String to,
                          Integer spreadPoints,
                          Integer slippagePoints,
                          String strategyKey,
                          Long strategyId) {

        /** 兼容构造：不关联真实策略行（回测落库改用内置规则行）。 */
        public RunSpec(String symbol, String timeframe, String from, String to,
                       Integer spreadPoints, Integer slippagePoints, String strategyKey) {
            this(symbol, timeframe, from, to, spreadPoints, slippagePoints, strategyKey, null);
        }
    }

    /** 多档扫描入参。 */
    public record TierSpec(String symbol,
                           String timeframe,
                           String from,
                           String to,
                           String strategyKey,
                           List<Integer> spreads,
                           List<Integer> slippages,
                           Long strategyId) {

        /** 兼容构造：不关联真实策略行。 */
        public TierSpec(String symbol, String timeframe, String from, String to,
                        String strategyKey, List<Integer> spreads, List<Integer> slippages) {
            this(symbol, timeframe, from, to, strategyKey, spreads, slippages, null);
        }
    }

    /** 可用策略视图。 */
    public record StrategyView(String key, String name) {
    }

    private record Resolved(String symbol, String timeframe, LocalDateTime from, LocalDateTime to) {
    }

    private record Stats(int tradeCount,
                         double winRatePct,
                         double profitFactor,
                         double maxDrawdown,
                         int avgHoldSec,
                         double expectancy,
                         double grossProfit,
                         double grossLoss,
                         double netProfit) {
    }
}
