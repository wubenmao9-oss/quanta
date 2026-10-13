package com.benmao.quanta.strategy;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.Strategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 策略库与回测验收门禁接口（M9）：
 * <ul>
 *   <li>{@code GET  /api/strategy/list}      — 幂等导入并列出首批策略（DRAFT）；</li>
 *   <li>{@code POST /api/strategy/{id}/gate} — 对某策略跑门禁，回执逐条判据；</li>
 *   <li>{@code POST /api/strategy/gate-all}  — 全部首批策略跑门禁 + A/B 对比结论；</li>
 *   <li>{@code POST /api/strategy/{id}/approve} — 人工批准：门禁全过才置 ACTIVE。</li>
 * </ul>
 *
 * <p>本层为<b>只读回测 + 策略状态变更</b>，不调 LLM（RULE 模式），不触碰任何交易写操作。
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    private final StrategyGateService gateService;

    public StrategyController(StrategyGateService gateService) {
        this.gateService = gateService;
    }

    /** 幂等导入并列出首批策略（DRAFT）。 */
    @GetMapping("/list")
    public Result<List<Strategy>> list() {
        return Result.ok(gateService.ensureCatalog());
    }

    /** 对单个策略执行门禁评估。 */
    @PostMapping("/{id}/gate")
    public Result<StrategyGateReport> gate(@PathVariable long id, @RequestBody(required = false) GateReq req) {
        GateReq r = req == null ? new GateReq(null, null, null) : req;
        return Result.ok(gateService.gate(id, r.symbol(), r.from(), r.to()));
    }

    /** 全部首批策略执行门禁 + GOLD 上 A/B 对比结论。 */
    @PostMapping("/gate-all")
    public Result<StrategyGateService.BatchReport> gateAll(@RequestBody(required = false) GateReq req) {
        GateReq r = req == null ? new GateReq(null, null, null) : req;
        return Result.ok(gateService.gateAll(r.symbol(), r.from(), r.to()));
    }

    /** 人工批准：重跑门禁，全部通过才置 ACTIVE。 */
    @PostMapping("/{id}/approve")
    public Result<Strategy> approve(@PathVariable long id, @RequestBody(required = false) GateReq req) {
        GateReq r = req == null ? new GateReq(null, null, null) : req;
        return Result.ok(gateService.approve(id, r.symbol(), r.from(), r.to()));
    }

    /** 门禁/批准请求体（均可空：缺省用 GOLD、库内已有 K 线全区间）。 */
    public record GateReq(String symbol, String from, String to) {
    }
}
