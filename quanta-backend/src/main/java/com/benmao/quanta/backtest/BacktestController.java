package com.benmao.quanta.backtest;

import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回测接口（M8）：单档回测 / 点差多档扫描 / 查报告 / 历史回填。
 *
 * <p>本层为<b>只读回测</b>，不触碰任何交易写操作，也不调用 LLM（RULE 模式）。
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService service;
    private final MarketHistoryService historyService;

    public BacktestController(BacktestService service, MarketHistoryService historyService) {
        this.service = service;
        this.historyService = historyService;
    }

    /** 可用规则策略清单。 */
    @GetMapping("/strategies")
    public Result<List<BacktestService.StrategyView>> strategies() {
        return Result.ok(service.availableStrategies());
    }

    /** 单档回测。 */
    @PostMapping("/run")
    public Result<BacktestReport> run(@RequestBody BacktestService.RunSpec spec) {
        return Result.ok(service.run(spec));
    }

    /** 点差/滑点多档扫描（设计 §13.4）。 */
    @PostMapping("/tiers")
    public Result<TierScanResult> tiers(@RequestBody BacktestService.TierSpec spec) {
        return Result.ok(service.runTiers(spec));
    }

    /** 读取某次回测报告（含明细）。 */
    @GetMapping("/{runId}")
    public Result<BacktestReport> getRun(@PathVariable long runId) {
        return Result.ok(service.getRun(runId));
    }

    /** 历史 K 线回填（拉最近 N 天 M1 落库，供回测读取）。 */
    @PostMapping("/backfill")
    public Result<BackfillResult> backfill(@RequestBody BackfillReq req) {
        return Result.ok(historyService.backfill(req.symbol(), req.timeframe(), req.days()));
    }

    /** 回填请求体。 */
    public record BackfillReq(String symbol, String timeframe, Integer days) {
    }
}
