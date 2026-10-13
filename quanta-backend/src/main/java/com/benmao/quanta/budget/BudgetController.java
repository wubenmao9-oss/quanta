package com.benmao.quanta.budget;

import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Token 预算与降级接口（M12，设计 §8.4）。
 *
 * <ul>
 *   <li>{@code GET  /api/budget/status}：预算状态（当日已用 / 预算 / 是否超限 / 是否降级）；</li>
 *   <li>{@code POST /api/budget/check}：立即执行一次检查（未超限只回报，超限则走降级流程）；</li>
 *   <li>{@code POST /api/budget/reset}：人工重置，解除降级态（幂等）。</li>
 * </ul>
 *
 * <p>本层不暴露任何绕过风控的下单入口——降级处置一律经 {@code TradingService}。
 */
@RestController
@RequestMapping("/api/budget")
public class BudgetController {

    private final BudgetGuard budgetGuard;

    public BudgetController(BudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
    }

    /** 预算状态（只读；跨日时顺带完成自动重置）。 */
    @GetMapping("/status")
    public Result<BudgetStatus> status() {
        return Result.ok(budgetGuard.status());
    }

    /** 立即执行一次预算检查；超限则触发降级流程（幂等可重入）。 */
    @PostMapping("/check")
    public Result<DegradeOutcome> check() {
        return Result.ok(budgetGuard.check());
    }

    /** 人工重置：解除降级态。 */
    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(@RequestBody(required = false) ResetReq req) {
        boolean changed = budgetGuard.reset(req == null ? null : req.reason());
        return Result.ok(Map.of("changed", changed, "degraded", false));
    }

    /** 人工重置入参。 */
    public record ResetReq(String reason) {
    }
}
