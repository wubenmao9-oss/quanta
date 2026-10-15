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
import com.fasterxml.jackson.databind.JsonNode;
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
    private final BasketStrategyProperties basketProps;
    private final MarketKlineMapper klineMapper;
    private final BacktestRunMapper runMapper;
    private final BacktestTradeMapper tradeMapper;
    private final StrategyMapper strategyMapper;
    private final Map<String, StrategyRef> strategies;
    private final Clock clock;

    @Autowired
    public BacktestService(BacktestProperties props,
                           BasketStrategyProperties basketProps,
                           MarketKlineMapper klineMapper,
                           BacktestRunMapper runMapper,
                           BacktestTradeMapper tradeMapper,
                           StrategyMapper strategyMapper,
                           List<RuleStrategy> ruleStrategies,
                           List<BasketStrategy> basketStrategies) {
        this(props, basketProps, klineMapper, runMapper, tradeMapper, strategyMapper, ruleStrategies,
                basketStrategies, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    BacktestService(BacktestProperties props,
                    BasketStrategyProperties basketProps,
                    MarketKlineMapper klineMapper,
                    BacktestRunMapper runMapper,
                    BacktestTradeMapper tradeMapper,
                    StrategyMapper strategyMapper,
                    List<RuleStrategy> ruleStrategies,
                    List<BasketStrategy> basketStrategies,
                    Clock clock) {
        this.props = props;
        this.basketProps = basketProps;
        this.klineMapper = klineMapper;
        this.runMapper = runMapper;
        this.tradeMapper = tradeMapper;
        this.strategyMapper = strategyMapper;
        Map<String, StrategyRef> map = new LinkedHashMap<>();
        for (RuleStrategy s : ruleStrategies) {
            map.put(s.key(), new StrategyRef(Family.SINGLE_LEG, s, null));
        }
        for (BasketStrategy s : basketStrategies) {
            map.put(s.key(), new StrategyRef(Family.BASKET, null, s));
        }
        this.strategies = map;
        this.clock = clock;
    }

    /** 可用策略清单（单仓 + 多腿/网格）。 */
    public List<StrategyView> availableStrategies() {
        List<StrategyView> out = new ArrayList<>();
        for (StrategyRef s : strategies.values()) {
            out.add(new StrategyView(s.key(), s.displayName(), s.family().name()));
        }
        return out;
    }

    /** 单档回测。 */
    public BacktestReport run(RunSpec spec) {
        Resolved r = resolve(spec.symbol(), spec.timeframe(), spec.from(), spec.to());
        StrategyRef strategy = strategyByKey(spec.strategyKey());
        Long strategyId = resolveStrategyId(spec.strategyId());
        int spread = spec.spreadPoints() == null ? 0 : spec.spreadPoints();
        int slippage = spec.slippagePoints() == null ? props.getDefaultSlippage() : spec.slippagePoints();
        validateSpread(spread);
        validateSlippage(slippage);
        double initialEquity = spec.initialEquity() == null ? props.getInitialEquity() : spec.initialEquity();
        return execute(r, strategy, spread, slippage, strategyId, initialEquity);
    }

    /** 点差/滑点多档扫描（设计 §13.4）。 */
    public TierScanResult runTiers(TierSpec spec) {
        Resolved r = resolve(spec.symbol(), spec.timeframe(), spec.from(), spec.to());
        StrategyRef strategy = strategyByKey(spec.strategyKey());
        Long strategyId = resolveStrategyId(spec.strategyId());
        double initialEquity = spec.initialEquity() == null ? props.getInitialEquity() : spec.initialEquity();

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
                BacktestReport report = execute(r, strategy, spread, slippage, strategyId, initialEquity);
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
        List<BacktestTrade> rows = tradeMapper.listByRunId(runId);
        List<ClosedTrade> trades = new ArrayList<>();
        boolean basket = false;
        for (BacktestTrade t : rows) {
            int cycleNo = t.getCycleNo() == null ? 0 : t.getCycleNo();
            if (cycleNo > 0) {
                basket = true;
            }
            String kind = (t.getEngineKind() == null || t.getEngineKind().isBlank()) ? "STORED" : t.getEngineKind();
            String reason = (t.getExitReason() == null || t.getExitReason().isBlank()) ? "STORED" : t.getExitReason();
            trades.add(new ClosedTrade(0, 0, t.getOpenTime(), t.getCloseTime(),
                    com.benmao.quanta.risk.RiskSide.valueOf(t.getCmd()),
                    d(t.getVolume()), d(t.getOpenPrice()), d(t.getClosePrice()),
                    d(t.getSl()), d(t.getTp()), d(t.getProfit()), d(t.getSwap()),
                    t.getHoldSec() == null ? 0 : t.getHoldSec(), reason, cycleNo,
                    t.getLevel() == null ? 0 : t.getLevel(), kind));
        }
        // 轮级口径：多腿策略按 cycle_no 聚合腿的净利为「轮」；单仓策略每笔即一轮。
        List<Double> unitNets = new ArrayList<>();
        if (basket) {
            Map<Integer, Double> byCycle = new LinkedHashMap<>();
            for (BacktestTrade t : rows) {
                int c = t.getCycleNo() == null ? 0 : t.getCycleNo();
                byCycle.merge(c, d(t.getProfit()) + d(t.getSwap()), Double::sum);
            }
            unitNets.addAll(byCycle.values());
        } else {
            for (BacktestTrade t : rows) {
                unitNets.add(d(t.getProfit()) + d(t.getSwap()));
            }
        }
        double net = unitNets.stream().mapToDouble(Double::doubleValue).sum();
        double grossProfit = unitNets.stream().mapToDouble(v -> Math.max(0, v)).sum();
        double grossLoss = unitNets.stream().mapToDouble(v -> Math.min(0, v)).sum();
        double expectancy = unitNets.isEmpty() ? 0 : net / unitNets.size();
        int maxLosing = longestLosingRun(unitNets);
        double avgLevels = unitNets.isEmpty() || !basket ? 1.0 : (double) rows.size() / unitNets.size();
        double initial = initialEquityOf(run);
        int cycleCount = basket ? unitNets.size() : rows.size();
        String name = run.getStrategyId() == null ? null : strategyName(run.getStrategyId());
        return new BacktestReport(run.getId(), null, name, run.getSymbol(), run.getDataTimeframe(),
                run.getStartTime(), run.getEndTime(), trades.size(), run.getSpreadMode(),
                d(run.getSpread()), d(run.getSlippage()), Boolean.TRUE.equals(run.getSwapOn()),
                run.getTradeCount() == null ? 0 : run.getTradeCount(),
                d(run.getWinRate()), d(run.getProfitFactor()), d(run.getMaxDrawdown()),
                run.getAvgHoldSec() == null ? 0 : run.getAvgHoldSec(), round2(expectancy),
                round2(grossProfit), round2(grossLoss), round2(net),
                initial, round2(initial + net),
                props.getDisclaimer(), trades, cycleCount, maxLosing, round2(avgLevels));
    }

    // ==================== 内部实现 ====================

    private BacktestReport execute(Resolved r, StrategyRef strategy, int spread, int slippage, Long strategyId,
                                   double initialEquity) {
        List<KlineBar> bars = loadBars(r);
        Stats stats;
        List<ClosedTrade> trades;
        double finalEquity;
        if (strategy.family() == Family.BASKET) {
            BasketReplayEngine engine = new BasketReplayEngine(props, basketProps, clock.getZone());
            BasketEngineResult result = engine.run(bars, strategy.basket(), spread, slippage, initialEquity);
            stats = computeBasketStats(result);
            trades = result.trades();
            finalEquity = result.finalEquity();
        } else {
            ReplayEngine engine = new ReplayEngine(props, clock.getZone());
            EngineResult result = engine.run(bars, strategy.rule().newRunner(), spread, slippage, initialEquity);
            stats = computeStats(result);
            trades = result.trades();
            finalEquity = result.finalEquity();
        }
        BacktestRun run = persistRun(r, strategy, spread, slippage, stats, strategyId, initialEquity);
        persistTrades(run.getId(), trades);
        log.info("回测完成：runId={}, strategy={}, family={}, symbol={}, timeframe={}, spread={}, slippage={}, "
                        + "bars={}, trades={}, cycles={}, winRate={}%, pf={}, net={}",
                run.getId(), strategy.key(), strategy.family(), r.symbol(), r.timeframe(), spread, slippage,
                bars.size(), stats.tradeCount(), stats.cycleCount(), stats.winRatePct(), stats.profitFactor(),
                stats.netProfit());
        String name = strategyId != null ? strategyName(strategyId) : strategy.displayName();
        return new BacktestReport(run.getId(), strategy.key(), name, r.symbol(), r.timeframe(),
                r.from(), r.to(), bars.size(), run.getSpreadMode(), spread, slippage, props.isSwapOn(),
                stats.tradeCount(), stats.winRatePct(), stats.profitFactor(), stats.maxDrawdown(),
                stats.avgHoldSec(), stats.expectancy(), stats.grossProfit(), stats.grossLoss(),
                stats.netProfit(), initialEquity, finalEquity, props.getDisclaimer(), trades,
                stats.cycleCount(), stats.maxLosingCycles(), stats.avgLevels());
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

    /** 单仓口径统计：每笔成交即一轮（cycleCount=tradeCount、avgLevels=1.0）。 */
    private Stats computeStats(EngineResult result) {
        List<ClosedTrade> trades = result.trades();
        List<Double> nets = new ArrayList<>(trades.size());
        int n = trades.size();
        double grossProfit = 0;
        double grossLoss = 0;
        double sumNet = 0;
        long sumHold = 0;
        int wins = 0;
        for (ClosedTrade t : trades) {
            double net = t.netProfit();
            nets.add(net);
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
                round2(grossProfit), round2(grossLoss), round2(net), n, longestLosingRun(nets), 1.0);
    }

    /** 多腿/网格口径统计：<b>轮级</b>（tradeCount/胜率/期望均按轮，而非按腿）。 */
    private Stats computeBasketStats(BasketEngineResult result) {
        List<BasketEngineResult.CycleSummary> cycles = result.cycles();
        int n = cycles.size();
        double grossProfit = 0;
        double grossLoss = 0;
        double sumNet = 0;
        double sumLevels = 0;
        long sumHold = 0;
        int wins = 0;
        List<Double> nets = new ArrayList<>(n);
        for (BasketEngineResult.CycleSummary c : cycles) {
            double net = c.netProfit();
            nets.add(net);
            sumNet += net;
            sumHold += c.holdSec();
            sumLevels += c.levels();
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
        double avgLevels = n == 0 ? 0 : sumLevels / n;
        return new Stats(n, round2(winRate), round2(Math.min(profitFactor, PROFIT_FACTOR_CAP)),
                round2(result.maxDrawdown()), avgHold, expectancy,
                round2(grossProfit), round2(grossLoss), round2(net), n,
                longestLosingRun(nets), round2(avgLevels));
    }

    /** 最长连续净亏「轮/笔」数（0 视为非亏，重置计数）。 */
    private static int longestLosingRun(List<Double> nets) {
        int max = 0;
        int cur = 0;
        for (double v : nets) {
            if (v < 0) {
                cur++;
                max = Math.max(max, cur);
            } else {
                cur = 0;
            }
        }
        return max;
    }

    private BacktestRun persistRun(Resolved r, StrategyRef strategy, int spread, int slippage, Stats stats,
                                   Long strategyId, double initialEquity) {
        BacktestRun run = new BacktestRun();
        run.setStrategyId(strategyId != null ? strategyId : ensureBuiltInStrategy(strategy, initialEquity));
        run.setSymbol(r.symbol());
        run.setDataTimeframe(r.timeframe());
        run.setStartTime(r.from());
        run.setEndTime(r.to());
        run.setParams(paramsJson(strategy, spread, slippage, initialEquity));
        run.setSpreadMode(spread == 0 ? "OFF" : "FIXED");
        run.setSpread(BigDecimal.valueOf(spread));
        run.setSlippage(BigDecimal.valueOf(slippage));
        run.setSwapOn(props.isSwapOn());
        run.setMode(strategy.family().name());
        run.setWinRate(BigDecimal.valueOf(stats.winRatePct()));
        run.setProfitFactor(BigDecimal.valueOf(stats.profitFactor()));
        run.setMaxDrawdown(BigDecimal.valueOf(stats.maxDrawdown()));
        run.setTradeCount(stats.tradeCount());
        run.setAvgHoldSec(stats.avgHoldSec());
        run.setCycleCount(stats.cycleCount());
        run.setMaxLosingCycles(stats.maxLosingCycles());
        run.setAvgLevels(BigDecimal.valueOf(stats.avgLevels()));
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
            e.setExitReason(t.exitReason());
            e.setCycleNo(t.cycleNo());
            e.setLevel(t.level());
            e.setEngineKind(t.engineKind());
            tradeMapper.insert(e);
        }
    }

    /** 幂等获取内置策略行（回测落库需要 strategy_id 非空）。 */
    private Long ensureBuiltInStrategy(StrategyRef strategy, double initialEquity) {
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
        s.setRegimeType(strategy.family() == Family.BASKET ? "RANGE" : "TREND");
        s.setTargetRr(BigDecimal.valueOf(props.getStrategy().getTargetRr()));
        s.setIndicatorParams(paramsJson(strategy, 0, props.getDefaultSlippage(), initialEquity));
        s.setDescription("M8 回测内置策略规则，作为 M9 真策略（Prompt → 规则）的落地模板");
        strategyMapper.insert(s);
        return s.getId();
    }

    private String strategyName(Long strategyId) {
        Strategy s = strategyMapper.getById(strategyId);
        return s == null ? null : s.getName();
    }

    private StrategyRef strategyByKey(String key) {
        String k = (key == null || key.isBlank()) ? com.benmao.quanta.backtest.rule.EmaAtrRuleStrategy.KEY : key.trim();
        StrategyRef s = strategies.get(k);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的策略：" + key + "，可选：" + strategies.keySet(), 400);
        }
        return s;
    }

    /** 从落库 params JSON 还原本次回测的初始权益（缺省回退配置默认值）。 */
    private double initialEquityOf(BacktestRun run) {
        String p = run.getParams();
        if (p != null && !p.isBlank()) {
            try {
                JsonNode n = MAPPER.readTree(p);
                if (n.hasNonNull("initialEquity")) {
                    return n.get("initialEquity").asDouble();
                }
            } catch (JsonProcessingException ignored) {
                // 老数据参数非 JSON 时回退默认
            }
        }
        return props.getInitialEquity();
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

    private String paramsJson(StrategyRef strategy, int spread, int slippage, double initialEquity) {
        Map<String, Object> m = new LinkedHashMap<>(strategy.indicatorParams());
        m.put("strategyKey", strategy.key());
        m.put("family", strategy.family().name());
        m.put("volume", props.getVolume());
        m.put("initialEquity", initialEquity);
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
                          Long strategyId,
                          Double initialEquity) {

        /** 兼容构造：不关联真实策略行（回测落库改用内置规则行）。 */
        public RunSpec(String symbol, String timeframe, String from, String to,
                       Integer spreadPoints, Integer slippagePoints, String strategyKey) {
            this(symbol, timeframe, from, to, spreadPoints, slippagePoints, strategyKey, null, null);
        }

        /** 兼容构造：不覆盖初始权益（回退配置默认值）。 */
        public RunSpec(String symbol, String timeframe, String from, String to,
                       Integer spreadPoints, Integer slippagePoints, String strategyKey, Long strategyId) {
            this(symbol, timeframe, from, to, spreadPoints, slippagePoints, strategyKey, strategyId, null);
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
                           Long strategyId,
                           Double initialEquity) {

        /** 兼容构造：不关联真实策略行、不覆盖初始权益。 */
        public TierSpec(String symbol, String timeframe, String from, String to,
                        String strategyKey, List<Integer> spreads, List<Integer> slippages) {
            this(symbol, timeframe, from, to, strategyKey, spreads, slippages, null, null);
        }
    }

    /** 可用策略视图（family=SINGLE_LEG/BASKET，供前端区分单仓与多腿/网格）。 */
    public record StrategyView(String key, String name, String family) {

        /** 兼容构造：默认单仓族。 */
        public StrategyView(String key, String name) {
            this(key, name, "SINGLE_LEG");
        }
    }

    /** 策略族：单仓（ReplayEngine）或多腿/网格（BasketReplayEngine）。 */
    private enum Family { SINGLE_LEG, BASKET }

    /** 策略解析引用（按族分派执行引擎）。 */
    private record StrategyRef(Family family, RuleStrategy rule, BasketStrategy basket) {

        String key() {
            return family == Family.BASKET ? basket.key() : rule.key();
        }

        String displayName() {
            return family == Family.BASKET ? basket.displayName() : rule.displayName();
        }

        Map<String, Object> indicatorParams() {
            return family == Family.BASKET ? basket.indicatorParams() : rule.indicatorParams();
        }
    }

    private record Resolved(String symbol, String timeframe, LocalDateTime from, LocalDateTime to) {
    }

    /** 回测统计（轮级口径：tradeCount/cycleCount 对多腿策略=轮数）。 */
    private record Stats(int tradeCount,
                         double winRatePct,
                         double profitFactor,
                         double maxDrawdown,
                         int avgHoldSec,
                         double expectancy,
                         double grossProfit,
                         double grossLoss,
                         double netProfit,
                         int cycleCount,
                         int maxLosingCycles,
                         double avgLevels) {
    }
}
