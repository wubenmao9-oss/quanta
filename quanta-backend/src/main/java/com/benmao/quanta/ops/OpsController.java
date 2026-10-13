package com.benmao.quanta.ops;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.reconcile.ReconcileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维接口（M13）：三态运行模式 + 状态观测 + 手工对账。
 *
 * <ul>
 *   <li>{@code GET  /api/ops/state}：运行模式 / 降级态 / 当日 Token 用量；</li>
 *   <li>{@code POST /api/ops/mode}：切换 AUTO / PAUSED / TAKEOVER（落库 + 审计 + 通知会话）；</li>
 *   <li>{@code POST /api/ops/reconcile}：立即对账（只报告差异，不改账）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;
    private final ReconcileService reconcileService;

    public OpsController(OpsService opsService, ReconcileService reconcileService) {
        this.opsService = opsService;
        this.reconcileService = reconcileService;
    }

    /** 当前运行状态快照。 */
    @GetMapping("/state")
    public Result<OpsService.OpsState> state() {
        return Result.ok(opsService.state());
    }

    /** 切换运行模式（非法模式 → 400 且回执合法取值）。 */
    @PostMapping("/mode")
    public Result<OpsService.OpsState> mode(@RequestBody ModeReq req) {
        opsService.setMode(req.mode());
        return Result.ok(opsService.state());
    }

    /** 立即对账（返回差异清单；已落审计与告警）。 */
    @PostMapping("/reconcile")
    public Result<ReconcileService.ReconcileReport> reconcile() {
        return Result.ok(reconcileService.reconcile());
    }

    /** 运行模式请求体。 */
    public record ModeReq(String mode) {
    }
}
