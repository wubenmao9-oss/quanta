package com.benmao.quanta.strategy;

import com.benmao.quanta.backtest.BacktestProperties;
import com.benmao.quanta.backtest.BacktestReport;
import com.benmao.quanta.backtest.BacktestService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.MarketKlineMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.strategy.StrategyGateReport.Criterion;
import com.benmao.quanta.strategy.StrategyGateReport.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略回测验收门禁服务（P0清单 M9 / 设计 §14.4）：对策略在 GOLD 上跑「全样本 / 样本内(A) / 样本外(B)」
 * 三段回测 → 逐条判定门禁 → 回执；<b>只有全部通过且经人工批准，才置 {@code ACTIVE}</b>。
 *
 * <p>未达标 → 回执失败项与合法边界（供回炉调参 → 重新回测）。
 * 门禁阈值与点差档全部走 {@link StrategyProperties.Gate} 集中配置。
 */
@Service
public class StrategyGateService {

    private static final Logger log = LoggerFactory.getLogger(StrategyGateService.class);

    private final StrategyProperties props;
    private final BacktestProperties backtestProps;
    private final StrategyMapper strategyMapper;
    private final MarketKlineMapper klineMapper;
    private final StrategyCatalogService catalog;
    private final BacktestService backtest;

    public StrategyGateService(StrategyProperties props,
                               BacktestProperties backtestProps,
                               StrategyMapper strategyMapper,
                               MarketKlineMapper klineMapper,
                               StrategyCatalogService catalog,
                               BacktestService backtest) {
        this.props = props;
        this.backtestProps = backtestProps;
        this.strategyMapper = strategyMapper;
        this.klineMapper = klineMapper;
        this.catalog = catalog;
        this.backtest = backtest;
    }

    /** 幂等导入首批两策略（DRAFT）并返回。 */
    public List<Strategy> ensureCatalog() {
        return catalog.ensureImported();
    }

    /** 对单个策略执行门禁评估（不改变策略状态）。 */
    public StrategyGateReport gate(long strategyId, String symbol, String from, String to) {
        Strategy s = strategyMapper.getById(strategyId);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略不存在：id=" + strategyId, 404);
        }
        String ruleKey = catalog.ruleKeyOf(s);
        if (s.getTargetRr() == null || s.getTargetRr().doubleValue() <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "策略未配置合法 target_rr：name=" + s.getName(), 400);
        }
        double targetRr = s.getTargetRr().doubleValue();
        String family = catalog.familyOfRuleKey(ruleKey);

        StrategyProperties.Gate cfg = props.getGate();
        String sym = (symbol == null || symbol.isBlank()) ? backtestProps.getSymbol() : symbol.trim();
        String tf = backtestProps.getTimeframe();
        Range range = resolveRange(sym, tf, from, to);
        LocalDateTime split = splitPoint(range, cfg.getInSampleRatio());

        Segment full = runSegment(sym, tf, range.from(), range.to(), cfg, ruleKey, strategyId);
        Segment is = runSegment(sym, tf, range.from(), split, cfg, ruleKey, strategyId);
        Segment oos = runSegment(sym, tf, split, range.to(), cfg, ruleKey, strategyId);

        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg, targetRr, full, is, oos, family);

        // 留痕：把回测胜率写回策略（expected_win_rate）
        s.setExpectedWinRate(BigDecimal.valueOf(full.winRatePct()));
        strategyMapper.update(s);

        log.info("门禁评估：strategyId={}, name={}, family={}, passed={}, full(cycles={}, trades={}, exp={}), oos(exp={})",
                strategyId, s.getName(), family, ev.passed(), full.cycleCount(), full.tradeCount(),
                full.expectancy(), oos.expectancy());

        return new StrategyGateReport(strategyId, s.getName(), ruleKey, sym, tf, s.getRegimeType(), targetRr,
                range.from(), range.to(), split, cfg.getInSampleRatio(), cfg.getSpreadPoints(), cfg.getSlippagePoints(),
                full, is, oos, ev.criteria(), ev.passed(), ev.abConclusion(), ev.verdict(),
                backtestProps.getDisclaimer());
    }

    /** 对全部首批策略执行门禁，并给出两策略在 GOLD 上的 A/B 对比结论。 */
    public BatchReport gateAll(String symbol, String from, String to) {
        List<StrategyGateReport> reports = new ArrayList<>();
        for (Strategy s : catalog.ensureImported()) {
            reports.add(gate(s.getId(), symbol, from, to));
        }
        return new BatchReport(reports, crossConclusion(reports));
    }

    /**
     * 人工批准并置为 {@code ACTIVE}：<b>先重跑门禁，全部通过才置 ACTIVE</b>；
     * 未通过则回执失败项（不改状态）。
     */
    public Strategy approve(long strategyId, String symbol, String from, String to) {
        StrategyGateReport report = gate(strategyId, symbol, from, to);
        if (!report.passed()) {
            List<String> failed = report.criteria().stream().filter(c -> !c.pass()).map(Criterion::name).toList();
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "门禁未通过，禁止置 ACTIVE；失败项：" + String.join("、", failed)
                            + "。请回炉调参后重新回测。", 400);
        }
        Strategy s = strategyMapper.getById(strategyId);
        if ("ACTIVE".equals(s.getStatus())) {
            return s; // 幂等
        }
        s.setStatus("ACTIVE");
        s.setApproved(true);
        s.setExpectedWinRate(BigDecimal.valueOf(report.full().winRatePct()));
        strategyMapper.update(s);
        log.info("策略已人工批准并置 ACTIVE：strategyId={}, name={}", strategyId, s.getName());
        return s;
    }

    // ==================== 演练模式（绕过门禁，仅用于验证 AI 是否真的会下单） ====================

    /**
     * 演练上线：<b>绕过门禁</b>把策略置为 {@code DRILL}，仅供「演练模式」验证决策链路能否真实下单。
     *
     * <p>安全约束：必须显式开启 {@code quanta.strategy.gate.drill-enabled}（默认关）才允许；否则 403 拒绝。
     * DRILL 策略<b>不</b>满足门禁（预期期望为负），严禁用于实盘资金；{@link #drillRevoke(long)} 可一键回滚。
     */
    public Strategy drillApprove(long strategyId) {
        if (!props.getGate().isDrillEnabled()) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "演练模式未开启（quanta.strategy.gate.drill-enabled=false），拒绝绕过门禁上线；"
                            + "请先显式开启该开关后重试", 403);
        }
        Strategy s = strategyMapper.getById(strategyId);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略不存在：id=" + strategyId, 404);
        }
        s.setStatus("DRILL");
        s.setApproved(false);
        s.setOrigin("DRILL");
        strategyMapper.update(s);
        log.warn("!!! 演练策略已上线（绕过门禁，仅用于验证 AI 能否真实下单，禁止用于实盘资金）："
                + "strategyId={}, name={}", strategyId, s.getName());
        return s;
    }

    /** 演练回滚：把 {@code DRILL} 策略退回 {@code DRAFT}（一键回滚，不需要开启演练开关）。 */
    public Strategy drillRevoke(long strategyId) {
        Strategy s = strategyMapper.getById(strategyId);
        if (s == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "策略不存在：id=" + strategyId, 404);
        }
        s.setStatus("DRAFT");
        s.setApproved(false);
        s.setOrigin("MANUAL");
        strategyMapper.update(s);
        log.info("演练策略已回滚为 DRAFT（不再用于研判）：strategyId={}, name={}", strategyId, s.getName());
        return s;
    }

    // ==================== 内部实现 ====================

    private Segment runSegment(String symbol, String timeframe, LocalDateTime from, LocalDateTime to,
                               StrategyProperties.Gate cfg, String ruleKey, long strategyId) {
        BacktestReport r = backtest.run(new BacktestService.RunSpec(symbol, timeframe,
                from.toString(), to.toString(), cfg.getSpreadPoints(), cfg.getSlippagePoints(), ruleKey, strategyId,
                cfg.getInitialEquity()));
        double base = cfg.getInitialEquity() <= 0 ? backtestProps.getInitialEquity() : cfg.getInitialEquity();
        double ddPct = base <= 0 ? 0 : round2(r.maxDrawdown() / base * 100.0);
        return new Segment(r.runId(), r.tradeCount(), r.winRatePct(), r.profitFactor(),
                round2(r.maxDrawdown()), ddPct, r.netProfit(), r.expectancy(),
                r.cycleCount(), r.maxLosingCycles(), r.avgLevels());
    }

    private Range resolveRange(String symbol, String timeframe, String from, String to) {
        LocalDateTime f = (from == null || from.isBlank()) ? klineMapper.minBarTime(symbol, timeframe) : parseTime(from, "from");
        LocalDateTime t = (to == null || to.isBlank()) ? klineMapper.maxBarTime(symbol, timeframe) : parseTime(to, "to");
        if (f == null || t == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "无可用 K 线数据（symbol=" + symbol + ", timeframe=" + timeframe
                            + "）；请先回填：POST /api/backtest/backfill", 400);
        }
        if (!f.isBefore(t)) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "时间区间非法：from(" + f + ") 必须早于 to(" + t + ")", 400);
        }
        return new Range(f, t);
    }

    private LocalDateTime splitPoint(Range range, double ratio) {
        if (ratio <= 0 || ratio >= 1) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "样本内比例非法：" + ratio + "，合法范围 (0, 1)（quanta.strategy.gate.in-sample-ratio）", 400);
        }
        long total = Duration.between(range.from(), range.to()).getSeconds();
        long isSec = Math.round(total * ratio);
        LocalDateTime split = range.from().plusSeconds(isSec);
        if (!split.isAfter(range.from())) {
            split = range.from().plusSeconds(1);
        }
        if (!split.isBefore(range.to())) {
            split = range.to().minusSeconds(1);
        }
        return split;
    }

    private String crossConclusion(List<StrategyGateReport> reports) {
        if (reports.size() < 2) {
            return "首批策略不足两份，无法进行 A/B 对比";
        }
        StringBuilder sb = new StringBuilder("GOLD A/B 对比：");
        for (int i = 0; i < reports.size(); i++) {
            StrategyGateReport r = reports.get(i);
            sb.append("策略").append((char) ('A' + i)).append("（").append(r.strategyName()).append("）")
                    .append("门禁").append(r.passed() ? "通过" : "未通过")
                    .append("，").append(r.spreadPoints()).append(" 点差档期望 ")
                    .append(round2(r.full().expectancy())).append("、胜率 ")
                    .append(round2(r.full().winRatePct())).append("%、回撤 ")
                    .append(round2(r.full().maxDrawdownPct())).append("%");
            sb.append(i == reports.size() - 1 ? "。" : "；");
        }
        long pass = reports.stream().filter(StrategyGateReport::passed).count();
        sb.append(pass == reports.size() ? "两策略均在 GOLD 上通过门禁。"
                : "仅 " + pass + "/" + reports.size() + " 通过门禁，未通过者须回炉调参后重新回测。");
        return sb.toString();
    }

    private static LocalDateTime parseTime(String raw, String field) {
        String v = raw.trim().replace(' ', 'T');
        if (v.length() == 10) {
            v = v + "T00:00:00";
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    field + " 解析失败：" + raw
                            + "，合法格式：2026-09-12T00:00:00 / 2026-09-12 00:00:00 / 2026-09-12", 400);
        }
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record Range(LocalDateTime from, LocalDateTime to) {
    }

    /** 批量门禁结果 + 两策略 A/B 对比结论。 */
    public record BatchReport(List<StrategyGateReport> reports, String abConclusion) {
    }
}
