package com.benmao.quanta.cycle;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实盘轮次（cycle）只读 + 人工干预接口：
 * <ul>
 *   <li>{@code GET  /api/cycle/list}              — 最近轮次；</li>
 *   <li>{@code GET  /api/cycle/{id}/legs}         — 某轮的腿明细；</li>
 *   <li>{@code POST /api/cycle/{id}/force-close}  — 人工强制平轮；</li>
 *   <li>{@code POST /api/cycle/unlock}            — 人工解除总亏锁；</li>
 *   <li>{@code GET  /api/cycle/settings}          — 读锁档（三层 + 锁定时长）；</li>
 *   <li>{@code PUT  /api/cycle/settings}          — 改锁档（0 = 不启用/无上限）。</li>
 * </ul>
 *
 * <p>本层不做自动交易；强制平轮仍经 {@code TradingService → RiskGuard}。
 */
@RestController
@RequestMapping("/api/cycle")
public class CycleController {

    private final CycleMapper cycleMapper;
    private final CycleLegMapper legMapper;
    private final CycleEngine engine;
    private final CycleLockService lockService;

    public CycleController(CycleMapper cycleMapper, CycleLegMapper legMapper,
                           CycleEngine engine, CycleLockService lockService) {
        this.cycleMapper = cycleMapper;
        this.legMapper = legMapper;
        this.engine = engine;
        this.lockService = lockService;
    }

    @GetMapping("/list")
    public Result<List<MartinCycle>> list(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.ok(cycleMapper.listRecent(Math.max(1, Math.min(limit, 200))));
    }

    @GetMapping("/{id}/legs")
    public Result<List<MartinCycleLeg>> legs(@PathVariable long id) {
        return Result.ok(legMapper.listByCycle(id));
    }

    @PostMapping("/{id}/force-close")
    public Result<MartinCycle> forceClose(@PathVariable long id,
                                          @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(engine.forceClose(id, reason));
    }

    @PostMapping("/unlock")
    public Result<String> unlock() {
        lockService.unlockTotal();
        return Result.ok("总亏锁已解除");
    }

    /** 读锁档：三层百分比 + 锁定时长（0 = 该档不启用/无上限）。 */
    @GetMapping("/settings")
    public Result<CycleSetting> settings() {
        return Result.ok(lockService.currentSetting());
    }

    /**
     * 改锁档：三层百分比 + 锁定时长；数值 0 表示该档不启用（无上限），留空/null 表示回落配置默认。
     *
     * <p>入参字段名与 {@link CycleSetting} 同名；容错接受驼峰（maxSingleLossPct）与下划线（max_single_loss_pct）。
     */
    @PutMapping("/settings")
    public Result<CycleSetting> saveSettings(@RequestBody Map<String, Object> body) {
        return Result.ok(lockService.saveSetting(
                toDouble(pick(body, "maxSingleLossPct", "max_single_loss_pct")),
                toDouble(pick(body, "maxDailyLossPct", "max_daily_loss_pct")),
                toDouble(pick(body, "maxTotalLossPct", "max_total_loss_pct")),
                toInt(pick(body, "lockHours", "lock_hours"))));
    }

    private static Object pick(Map<String, Object> body, String camel, String snake) {
        return body.containsKey(camel) ? body.get(camel) : body.get(snake);
    }

    private static Double toDouble(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "百分比须为数字：" + v, 400);
        }
    }

    private static Integer toInt(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "锁定时长须为整数：" + v, 400);
        }
    }
}
