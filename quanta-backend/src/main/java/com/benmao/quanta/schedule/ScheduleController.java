package com.benmao.quanta.schedule;

import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度层接口：观测盯盘/触发状态，并手动唤醒（T6）。
 *
 * <p>本层不暴露任何绕过风控的下单入口。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final TriggerEngine triggerEngine;
    private final SessionManager sessionManager;
    private final MarketWatcher marketWatcher;
    private final ScheduleProperties props;

    public ScheduleController(TriggerEngine triggerEngine,
                              SessionManager sessionManager,
                              MarketWatcher marketWatcher,
                              ScheduleProperties props) {
        this.triggerEngine = triggerEngine;
        this.sessionManager = sessionManager;
        this.marketWatcher = marketWatcher;
        this.props = props;
    }

    /** 调度层总览：盯盘状态 + 触发开关 + 会话窗口 + 最近命中。 */
    @GetMapping("/status")
    public Result<StatusView> status() {
        MarketSnapshot snap = marketWatcher.snapshot();
        MarketView market = new MarketView(
                marketWatcher.connected(),
                marketWatcher.symbol(),
                marketWatcher.timeframe(),
                marketWatcher.consecutiveFailures(),
                snap == null ? null : snap.barTime(),
                snap == null ? null : snap.last(),
                snap == null ? null : snap.capturedAt());
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (TriggerSource source : TriggerSource.values()) {
            switches.put(source.name(), props.getTrigger().enabled(source));
        }
        return Result.ok(new StatusView(
                props.isEnabled(),
                sessionManager.maxSession(),
                sessionManager.activeCount(),
                sessionManager.queuedCount(),
                switches,
                market,
                triggerEngine.recentHits()));
    }

    /** 人工唤醒（T6）：登记一次唤醒，由下一次扫描分发。 */
    @PostMapping("/wake")
    public Result<Map<String, Object>> wake(@RequestBody(required = false) WakeReq req) {
        triggerEngine.manualWake(req == null ? null : req.reason());
        return Result.ok(Map.of("accepted", true));
    }

    /** 人工唤醒入参。 */
    public record WakeReq(String reason) {
    }

    /**
     * 调度层状态视图。
     *
     * @param enabled        总开关
     * @param maxSession     会话窗口上限
     * @param activeSessions 活跃窗口数
     * @param queued         排队数
     * @param switches       各触发源生效开关
     * @param marketWatcher  盯盘状态
     * @param recentHits     最近命中
     */
    public record StatusView(boolean enabled,
                             int maxSession,
                             int activeSessions,
                             int queued,
                             Map<String, Boolean> switches,
                             MarketView marketWatcher,
                             List<TriggerHit> recentHits) {
    }

    /**
     * 盯盘状态视图。
     *
     * @param connected           是否已连接
     * @param symbol              品种
     * @param timeframe           周期
     * @param consecutiveFailures 连续失败次数
     * @param lastBarTime         最新 K 线时间
     * @param last                最新价
     * @param capturedAt          快照时刻
     */
    public record MarketView(boolean connected,
                             String symbol,
                             String timeframe,
                             long consecutiveFailures,
                             LocalDateTime lastBarTime,
                             Double last,
                             Instant capturedAt) {
    }
}
