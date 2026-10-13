package com.benmao.quanta.review;

import com.benmao.quanta.backtest.BacktestProperties;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 复盘引擎（M11，设计 §12）：<b>统计复盘，不训练模型</b>。
 *
 * <p>五项产出：
 * <ol>
 *   <li><b>单笔复盘</b>：{@link #generateReport(Long)} —— 动作时间线 + 结果 + 归因，落 {@code trade_report}；</li>
 *   <li><b>周期统计</b>：{@link #stats(String, LocalDateTime, LocalDateTime)} —— 胜率/盈亏比/最大回撤/连亏；</li>
 *   <li><b>败因分类</b>：{@link #classifyLosses(String, LocalDateTime, LocalDateTime)}；</li>
 *   <li><b>策略评价</b>：{@link #evaluateStrategies(Long, LocalDateTime, LocalDateTime)} —— 含「是否该切换策略」建议；</li>
 *   <li><b>成本分析</b>：{@link #analyzeCost(Long)} —— 点差/滑点/swap 占比，识别「是否被点差吃掉利润」。</li>
 * </ol>
 *
 * <p><b>自动出报告</b>：监听 {@link TradeClosedEvent}（平仓事务 <b>提交后</b>触发），按 {@code order_id} 幂等
 * 生成单笔报告——报告生成失败<b>绝不回滚</b>已成交的平仓（监听器内部吞异常并告警）。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewProperties props;
    private final TradeOrderMapper tradeOrderMapper;
    private final TradeReportMapper tradeReportMapper;
    private final AgentDecisionLogMapper decisionLogMapper;
    private final StrategyMapper strategyMapper;
    private final BacktestRunMapper backtestRunMapper;
    private final BacktestTradeMapper backtestTradeMapper;
    private final BacktestProperties backtestProps;
    private final ObjectMapper json;

    public ReviewService(ReviewProperties props,
                         TradeOrderMapper tradeOrderMapper,
                         TradeReportMapper tradeReportMapper,
                         AgentDecisionLogMapper decisionLogMapper,
                         StrategyMapper strategyMapper,
                         BacktestRunMapper backtestRunMapper,
                         BacktestTradeMapper backtestTradeMapper,
                         BacktestProperties backtestProps,
                         ObjectMapper json) {
        this.props = props;
        this.tradeOrderMapper = tradeOrderMapper;
        this.tradeReportMapper = tradeReportMapper;
        this.decisionLogMapper = decisionLogMapper;
        this.strategyMapper = strategyMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.backtestTradeMapper = backtestTradeMapper;
        this.backtestProps = backtestProps;
        this.json = json;
    }

    // ==================== 1. 单笔复盘 ====================

    /**
     * 平仓事件监听（设计 §5：平仓 → 生成全周期报告）。
     *
     * <p>挂在 {@code AFTER_COMMIT} 且用独立事务：平仓已提交才触发；报告写失败只告警，不影响成交结果。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTradeClosed(TradeClosedEvent event) {
        if (!props.isEnabled() || event == null || event.order() == null) {
            return;
        }
        Long orderId = event.order().getId();
        try {
            TradeReport r = generateReport(orderId);
            log.info("平仓自动复盘完成 orderId={} reportId={} result={} pnl={}",
                    orderId, r.getId(), r.getResult(), r.getPnl());
        } catch (RuntimeException e) {
            log.warn("平仓自动复盘失败（不影响已成交的平仓）：orderId={} err={}", orderId, e.getMessage());
        }
    }

    /**
     * 生成单笔全周期报告（幂等：同一订单已存在报告则直接返回）。
     *
     * @param orderId 已平仓订单 id
     */
    @Transactional
    public TradeReport generateReport(Long orderId) {
        if (orderId == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "orderId 不能为空", 400);
        }
        TradeOrder order = tradeOrderMapper.getById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "订单不存在: id=" + orderId, 404);
        }
        if (!"CLOSED".equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "订单未平仓（status=" + order.getStatus() + "），无法生成复盘报告", 400);
        }
        TradeReport existing = tradeReportMapper.findByOrderId(orderId);
        if (existing != null) {
            return existing;
        }

        BigDecimal pnl = BigDecimal.valueOf(pnlOf(order));
        TradeReport report = new TradeReport();
        report.setOrderId(order.getId());
        report.setSessionId(order.getSessionId());
        report.setActionTimeline(renderTimeline(order));
        report.setSummary(renderSingleSummary(order, pnl.doubleValue()));
        report.setPnl(pnl);
        report.setResult(resultOf(pnl.doubleValue()));
        report.setReviewed(false);
        tradeReportMapper.insert(report);
        return report;
    }

    /** 读取某订单的报告（不存在则 404）。 */
    public TradeReport getReport(Long orderId) {
        if (orderId == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "orderId 不能为空", 400);
        }
        TradeReport r = tradeReportMapper.findByOrderId(orderId);
        if (r == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "订单 " + orderId + " 暂无复盘报告", 404);
        }
        return r;
    }

    /** 最近若干份单笔报告（最新在前）。 */
    public List<TradeReport> listReports(int limit) {
        int n = limit > 0 ? Math.min(limit, 200) : props.getListDefaultLimit();
        return tradeReportMapper.listRecent(n);
    }

    // ==================== 2. 周期统计 ====================

    /** 周期统计（胜率/盈亏比/最大回撤/连亏次数/平均持仓）。 */
    public PeriodStats stats(String symbol, LocalDateTime since, LocalDateTime until) {
        LocalDateTime from = resolveSince(since);
        List<TradeOrder> orders = tradeOrderMapper.listClosedBetween(blankToNull(symbol), from, until);
        Metrics m = compute(orders);
        return new PeriodStats(blankToNull(symbol), from, until,
                m.tradeCount(), m.winCount(), m.lossCount(), m.winRatePct(),
                m.grossProfit(), m.grossLoss(), m.profitFactor(), m.netProfit(),
                m.expectancy(), m.maxDrawdown(), m.maxConsecutiveLoss(), m.avgHoldSec());
    }

    // ==================== 3. 败因分类 ====================

    /** 败因分类（无信号错过 / 逆势 / 早平 / 晚平 / 系统错误）。 */
    public LossBreakdown classifyLosses(String symbol, LocalDateTime since, LocalDateTime until) {
        LocalDateTime from = resolveSince(since);
        List<TradeOrder> orders = tradeOrderMapper.listClosedBetween(blankToNull(symbol), from, until);
        int lossCount = 0;
        int earlyClose = 0;
        int lateClose = 0;
        int systemError = 0;
        for (TradeOrder o : orders) {
            double p = pnlOf(o);
            int hold = holdSecOf(o);
            if (o.getRetcode() != null && o.getRetcode() != 0) {
                systemError++;
            }
            if (p > 0 && hold >= 0 && hold < props.getEarlyCloseSec()) {
                earlyClose++;
            }
            if (p < 0) {
                lossCount++;
                if (hold >= props.getLateCloseSec()) {
                    lateClose++;
                }
            }
        }
        int counterTrendLoss = Math.max(0, lossCount - lateClose);
        int missedWaits = decisionLogMapper.countByActionBetween("WAIT", from, until);

        List<String> notes = new ArrayList<>();
        notes.add("早平 = 盈利单持仓 < " + props.getEarlyCloseSec() + " 秒（错失后续利润）");
        notes.add("晚平 = 亏损单持仓 ≥ " + props.getLateCloseSec() + " 秒（扛单过久）");
        notes.add("系统错误 = 券商返回码 retcode != 0");
        notes.add("逆势亏损 = 其余亏损单（方向判断错误）");
        notes.add("无信号错过 = 区间内 AI 判 WAIT 的决策次数");

        return new LossBreakdown(blankToNull(symbol), from, until, orders.size(), lossCount,
                earlyClose, lateClose, systemError, counterTrendLoss, missedWaits, notes);
    }

    // ==================== 4. 策略评价 ====================

    /**
     * 策略评价（按策略维度统计 + 「是否该切换策略」建议）。
     *
     * @param strategyId 指定策略（可空 = 评价全部策略）
     */
    public List<StrategyEvaluation> evaluateStrategies(Long strategyId, LocalDateTime since, LocalDateTime until) {
        LocalDateTime from = resolveSince(since);
        List<Strategy> strategies;
        if (strategyId != null) {
            Strategy s = strategyMapper.getById(strategyId);
            if (s == null) {
                throw new BizException(ResultCode.NOT_FOUND.code(), "策略不存在: id=" + strategyId, 404);
            }
            strategies = List.of(s);
        } else {
            strategies = strategyMapper.listAll();
        }
        List<StrategyEvaluation> out = new ArrayList<>();
        for (Strategy s : strategies) {
            List<TradeOrder> orders = tradeOrderMapper.listClosedByStrategy(s.getId(), from, until);
            Metrics m = compute(orders);
            out.add(new StrategyEvaluation(s.getId(), s.getName(), s.getStatus(), m.tradeCount(),
                    m.winRatePct(), m.profitFactor(), m.expectancy(), m.maxDrawdown(),
                    verdictOf(m), adviceOf(m)));
        }
        return out;
    }

    // ==================== 5. 成本分析 ====================

    /**
     * 成本拆解（点差 / 滑点 / swap 占比）与「是否被点差吃掉利润」判定。
     *
     * <p>数据基准 = 回测明细（显式可拆）。成本每边各计一次，往返 2 次成交。
     */
    public CostAnalysis analyzeCost(Long runId) {
        if (runId == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "runId 不能为空", 400);
        }
        BacktestRun run = backtestRunMapper.getById(runId);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND.code(), "回测任务不存在: runId=" + runId, 404);
        }
        List<BacktestTrade> trades = backtestTradeMapper.listByRunId(runId);

        double point = backtestProps.getPoint();
        double contract = backtestProps.getContractSizePerLot();
        double factor = backtestProps.getSpreadSideFactor();
        double spreadPoints = num(run.getSpread());
        double slippagePoints = num(run.getSlippage());

        double spreadCost = 0.0;
        double slippageCost = 0.0;
        double swapCost = 0.0;
        double profitSum = 0.0;
        double swapSum = 0.0;
        for (BacktestTrade t : trades) {
            double volume = num(t.getVolume());
            spreadCost += spreadPoints * factor * point * contract * volume * 2.0;
            slippageCost += slippagePoints * point * contract * volume * 2.0;
            double swap = num(t.getSwap());
            swapCost += Math.abs(swap);
            swapSum += swap;
            profitSum += num(t.getProfit());
        }
        double netProfit = profitSum + swapSum;
        // 还原「未扣点差/滑点」的理想毛利润：把成本加回已实现盈亏
        double grossProfit = profitSum + spreadCost + slippageCost;
        double totalCost = spreadCost + slippageCost + swapCost;
        boolean eatenByCost = grossProfit > 0.0 && netProfit <= 0.0;

        return new CostAnalysis(runId, run.getStrategyId(), run.getSymbol(), run.getDataTimeframe(),
                spreadPoints, slippagePoints, Boolean.TRUE.equals(run.getSwapOn()), trades.size(),
                netProfit, grossProfit, spreadCost, slippageCost, swapCost, totalCost,
                pct(spreadCost, totalCost), pct(slippageCost, totalCost), pct(swapCost, totalCost),
                eatenByCost, costVerdict(trades.size(), netProfit, grossProfit, totalCost, spreadCost,
                spreadPoints, eatenByCost), backtestProps.getDisclaimer());
    }

    // ==================== 内部实现：指标计算 ====================

    /** 从已平仓单序列计算统计指标（周期统计与策略评价共用）。 */
    private Metrics compute(List<TradeOrder> orders) {
        int count = orders.size();
        int win = 0;
        int loss = 0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        double net = 0.0;
        long holdSum = 0;
        int holdN = 0;
        int maxConsecLoss = 0;
        int curConsecLoss = 0;

        double equity = props.getInitialEquity();
        double peak = equity;
        double maxDrawdown = 0.0;

        for (TradeOrder o : orders) {
            double pnl = pnlOf(o);
            net += pnl;
            if (pnl > 0) {
                win++;
                grossProfit += pnl;
                curConsecLoss = 0;
            } else if (pnl < 0) {
                loss++;
                grossLoss += -pnl;
                curConsecLoss++;
                maxConsecLoss = Math.max(maxConsecLoss, curConsecLoss);
            }
            equity += pnl;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
            int hold = holdSecOf(o);
            if (hold >= 0) {
                holdSum += hold;
                holdN++;
            }
        }

        double winRate = count == 0 ? 0.0 : win * 100.0 / count;
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss
                : (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0.0);
        double expectancy = count == 0 ? 0.0 : net / count;
        int avgHold = holdN == 0 ? 0 : (int) (holdSum / holdN);

        return new Metrics(count, win, loss, winRate, grossProfit, grossLoss, profitFactor,
                net, expectancy, maxDrawdown, maxConsecLoss, avgHold);
    }

    private String verdictOf(Metrics m) {
        if (m.tradeCount() == 0) {
            return "无样本";
        }
        int min = props.getMinSampleForVerdict();
        if (m.tradeCount() < min) {
            return "样本不足（" + m.tradeCount() + "/" + min + "）";
        }
        return "样本充分";
    }

    private String adviceOf(Metrics m) {
        int min = props.getMinSampleForVerdict();
        if (m.tradeCount() == 0) {
            return "该策略暂无已平仓交易，无法评价；建议继续观察。";
        }
        if (m.tradeCount() < min) {
            return "样本不足（" + m.tradeCount() + "/" + min + "），暂不据此切换策略；建议继续积累样本。";
        }
        boolean pass = m.profitFactor() >= props.getMinProfitFactorForPass() && m.netProfit() > 0;
        if (pass) {
            return "表现达标（盈亏比 " + fmt(m.profitFactor()) + "、净盈亏 " + fmt(m.netProfit())
                    + " USD），建议维持当前策略。";
        }
        return "表现不佳（盈亏比 " + fmt(m.profitFactor()) + "、净盈亏 " + fmt(m.netProfit())
                + " USD），建议考虑切换至其余在测策略，或回炉调参后重新回测再上线。";
    }

    // ==================== 内部实现：单笔报告拼装 ====================

    /** 动作时间线：沿 trace 串起「决策 → 开仓 → 平仓」。 */
    private String renderTimeline(TradeOrder order) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (order.getTraceId() != null && !order.getTraceId().isBlank()) {
            for (AgentDecisionLog d : decisionLogMapper.listByTraceId(order.getTraceId())) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("time", str(d.getCreateTime()));
                e.put("type", "DECISION");
                e.put("action", d.getAction());
                e.put("confidence", d.getConfidence());
                e.put("reason", d.getReason());
                events.add(e);
            }
        }
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("time", str(order.getOpenTime()));
        open.put("type", "OPEN");
        open.put("cmd", order.getCmd());
        open.put("volume", order.getVolume());
        open.put("price", order.getPrice());
        open.put("sl", order.getSl());
        open.put("tp", order.getTp());
        events.add(open);

        Map<String, Object> close = new LinkedHashMap<>();
        close.put("time", str(order.getCloseTime()));
        close.put("type", "CLOSE");
        close.put("price", order.getClosePrice());
        close.put("profit", order.getProfit());
        close.put("swap", order.getSwap());
        events.add(close);

        try {
            return json.writeValueAsString(events);
        } catch (Exception e) {
            log.warn("动作时间线序列化失败：orderId={} err={}", order.getId(), e.getMessage());
            return "[]";
        }
    }

    private static String renderSingleSummary(TradeOrder order, double pnl) {
        int hold = holdSecOf(order);
        return "订单 " + order.getId() + " " + order.getSymbol() + " " + order.getCmd()
                + " " + plain(order.getVolume()) + " 手："
                + "开 " + plain(order.getPrice()) + " → 平 " + plain(order.getClosePrice())
                + "，持仓 " + (hold < 0 ? "-" : hold) + " 秒，"
                + "盈亏 " + fmt(pnl) + " USD（profit " + plain(order.getProfit())
                + " + swap " + plain(order.getSwap()) + "），结果 " + resultOf(pnl) + "。";
    }

    private static String resultOf(double pnl) {
        if (pnl > 0) {
            return "WIN";
        }
        if (pnl < 0) {
            return "LOSS";
        }
        return "FLAT";
    }

    private static String costVerdict(int tradeCount, double netProfit, double grossProfit,
                                      double totalCost, double spreadCost, double spreadPoints,
                                      boolean eatenByCost) {
        if (tradeCount == 0) {
            return "该回测无成交明细，无法拆解成本。";
        }
        String base = "成本合计 " + fmt(totalCost) + " USD（其中点差 " + fmt(spreadCost)
                + " USD，档位 " + fmt(spreadPoints) + " points）；毛利润 " + fmt(grossProfit)
                + " USD，净利 " + fmt(netProfit) + " USD。";
        if (eatenByCost) {
            return "毛利润为正却被点差/滑点/隔夜费吃掉，净利为负——判定「被点差吃掉利润」，该档位不可交易。" + base;
        }
        return (netProfit > 0 ? "净利为正。" : "净利为负。") + base;
    }

    // ==================== 内部实现：通用 ====================

    /** since 未显式给出时，回落到「当前 − 默认回看天数」；回看天数 ≤0 则不限制。 */
    private LocalDateTime resolveSince(LocalDateTime since) {
        if (since != null) {
            return since;
        }
        int days = props.getDefaultLookbackDays();
        return days > 0 ? LocalDateTime.now().minusDays(days) : null;
    }

    /** 单笔净盈亏（USD）＝ profit + swap。 */
    private static double pnlOf(TradeOrder o) {
        return num(o.getProfit()) + num(o.getSwap());
    }

    /** 持仓秒数；开/平时间缺失时返回 -1。 */
    private static int holdSecOf(TradeOrder o) {
        if (o.getOpenTime() == null || o.getCloseTime() == null) {
            return -1;
        }
        long s = Duration.between(o.getOpenTime(), o.getCloseTime()).getSeconds();
        return (int) Math.max(0, s);
    }

    private static Double pct(double part, double total) {
        return total == 0 ? null : part / total * 100.0;
    }

    private static double num(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String fmt(double v) {
        if (Double.isInfinite(v)) {
            return "∞";
        }
        if (Double.isNaN(v)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 统计中间值（周期统计与策略评价共用）。 */
    private record Metrics(int tradeCount,
                           int winCount,
                           int lossCount,
                           double winRatePct,
                           double grossProfit,
                           double grossLoss,
                           double profitFactor,
                           double netProfit,
                           double expectancy,
                           double maxDrawdown,
                           int maxConsecutiveLoss,
                           int avgHoldSec) {
    }
}
