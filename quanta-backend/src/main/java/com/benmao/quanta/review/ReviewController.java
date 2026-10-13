package com.benmao.quanta.review;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.TradeReport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 复盘接口（M11，设计 §12）。
 *
 * <ul>
 *   <li>{@code GET  /api/review/report/{orderId}}：读某笔的单笔复盘报告；</li>
 *   <li>{@code POST /api/review/report/{orderId}}：为某笔已平仓单生成报告（幂等，手工/验收用）；</li>
 *   <li>{@code GET  /api/review/reports}：最近若干份报告；</li>
 *   <li>{@code GET  /api/review/stats}：周期统计（胜率/盈亏比/最大回撤/连亏）；</li>
 *   <li>{@code GET  /api/review/losses}：败因分类；</li>
 *   <li>{@code GET  /api/review/strategy}：策略评价（含是否该切换策略建议）；</li>
 *   <li>{@code GET  /api/review/cost}：回测成本拆解（点差/滑点/swap 占比）。</li>
 * </ul>
 *
 * <p>本层为只读统计（除生成报告外不写库），不调用大模型。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /** 读取某订单的单笔复盘报告（不存在 404）。 */
    @GetMapping("/report/{orderId}")
    public Result<TradeReport> report(@PathVariable Long orderId) {
        return Result.ok(service.getReport(orderId));
    }

    /** 为某已平仓订单生成单笔复盘报告（幂等：已存在则返回既有报告）。 */
    @PostMapping("/report/{orderId}")
    public Result<TradeReport> generate(@PathVariable Long orderId) {
        return Result.ok(service.generateReport(orderId));
    }

    /** 最近若干份单笔报告。 */
    @GetMapping("/reports")
    public Result<List<TradeReport>> reports(@RequestParam(defaultValue = "0") int limit) {
        return Result.ok(service.listReports(limit));
    }

    /** 周期统计（symbol/since/until 均可空）。 */
    @GetMapping("/stats")
    public Result<PeriodStats> stats(@RequestParam(required = false) String symbol,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {
        return Result.ok(service.stats(symbol, since, until));
    }

    /** 败因分类（symbol/since/until 均可空）。 */
    @GetMapping("/losses")
    public Result<LossBreakdown> losses(@RequestParam(required = false) String symbol,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {
        return Result.ok(service.classifyLosses(symbol, since, until));
    }

    /** 策略评价（strategyId 可空 = 全部策略）。 */
    @GetMapping("/strategy")
    public Result<List<StrategyEvaluation>> strategy(@RequestParam(required = false) Long strategyId,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {
        return Result.ok(service.evaluateStrategies(strategyId, since, until));
    }

    /** 回测成本拆解（识别是否被点差吃掉利润）。 */
    @GetMapping("/cost")
    public Result<CostAnalysis> cost(@RequestParam Long runId) {
        return Result.ok(service.analyzeCost(runId));
    }
}
