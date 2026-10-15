package com.benmao.quanta.risk;

import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 风控层接口：供人工验收与后续 Agent 工具（M10）复用。
 *
 * <p>所有写操作在真正执行前都必须先走 {@code /evaluate}；被拒时返回结构化原因 + 合法边界值。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskGuard riskGuard;
    private final RiskConfigService riskConfig;

    public RiskController(RiskGuard riskGuard, RiskConfigService riskConfig) {
        this.riskGuard = riskGuard;
        this.riskConfig = riskConfig;
    }

    /** 校验一次写操作（OPEN/CLOSE/MODIFY），返回放行/拒绝 + 逐条原因。 */
    @PostMapping("/evaluate")
    public Result<RiskDecision> evaluate(@RequestBody EvaluateRequest req) {
        RiskRequest request = new RiskRequest(
                RiskAction.parse(req.action()),
                req.symbol(),
                req.side() == null ? null : RiskSide.parse(req.side()),
                req.volume(),
                req.price(),
                req.sl(),
                req.tp(),
                req.tpMode(),
                req.ticket(),
                req.sessionId(),
                req.traceId());
        return Result.ok(riskGuard.evaluate(request));
    }

    /** 回显当前生效阈值（来源 risk_config 表，人工可改）与运行模式，便于核对配置是否生效。 */
    @GetMapping("/thresholds")
    public Result<ThresholdsView> thresholds() {
        return Result.ok(new ThresholdsView(riskConfig.thresholds(), riskConfig.runMode()));
    }

    /**
     * 保存账户级风控阈值（写 {@code risk_config} 唯一行）。
     *
     * <p>含 R6 日亏熔断（{@code maxDailyLoss}，USD）：当日已实现亏损达到该值即禁止开仓，
     * 自然日 00:00 自动解除。<b>0 不是「关闭」而是「一亏即熔断」</b>，要实质关闭须填一个很大的数。
     * 入参只需给要改的字段（null = 保持当前值）。
     */
    @PutMapping("/thresholds")
    public Result<ThresholdsView> saveThresholds(@RequestBody RiskConfigService.Patch body) {
        return Result.ok(new ThresholdsView(riskConfig.saveThresholds(body), riskConfig.runMode()));
    }

    /** 校验请求（字符串形式的动作/方向，便于宽容入参并给出可选值回执）。 */
    public record EvaluateRequest(String action,
                                  String symbol,
                                  String side,
                                  BigDecimal volume,
                                  BigDecimal price,
                                  BigDecimal sl,
                                  BigDecimal tp,
                                  String tpMode,
                                  Long ticket,
                                  Long sessionId,
                                  String traceId) {
    }

    /** 阈值回显。 */
    public record ThresholdsView(RiskThresholds thresholds, String runMode) {
    }
}
