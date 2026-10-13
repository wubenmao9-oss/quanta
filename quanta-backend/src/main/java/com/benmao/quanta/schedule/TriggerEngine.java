package com.benmao.quanta.schedule;

import com.benmao.mt5.model.Position;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.TradePlanMapper;
import com.benmao.quanta.ops.AlertService;
import com.benmao.quanta.state.SystemStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 触发引擎：判断「何时唤醒 AI」。命中一个触发源即产出 {@link TriggerHit} 并交由 {@link SessionManager} 分配会话。
 *
 * <p><b>省 Token 的关键</b>：本类只做廉价判定，<b>不持有也不调用任何 LLM</b>；命中后是否真正发起 AI 研判属于 M10，
 * 一期到此为止（因此盯盘 + 触发全程 0 token）。
 *
 * <p>触发源（设计 §6.2）：T1/T2/T5/T6/T7 为内建，T3/T4 为插件（{@link TriggerRule}）。每个触发源都有独立开关，
 * 由 {@link ScheduleProperties.Trigger#enabled(TriggerSource)} 控制。
 *
 * <p><b>降级门控（M12）</b>：当 {@code sys_state.degraded=true}（Token 预算超限）时，扫描与分发<b>全部关闭触发</b>
 * （设计 §8.4）——读取降级态失败时按「未降级」处理（fail-open），避免库抖动使盯盘整体停摆。
 *
 * <p>鲁棒性：单个触发的判定/分发异常被隔离，绝不中断扫描循环。
 */
@Service
public class TriggerEngine {

    private static final Logger log = LoggerFactory.getLogger(TriggerEngine.class);

    /** 浮点比较容差。 */
    private static final double EPS = 1e-9;

    /** 命中历史保留条数（供 /api/schedule/status 观测）。 */
    private static final int HIT_HISTORY = 50;

    private final MarketWatcher watcher;
    private final Mt5BridgeService bridge;
    private final TradePlanMapper tradePlanMapper;
    private final SessionManager sessionManager;
    private final ScheduleProperties props;
    private final List<TriggerRule> rules;
    private final SystemStateService stateService;
    private final AlertService alertService;
    private final Clock clock;

    /** 上次时间驱动触发时刻（T2）。 */
    private volatile Instant lastTimeFireAt;
    /** 上次见到的 K 线时间（T2 barClose 用）。 */
    private volatile LocalDateTime lastBarTime;
    /** 上次连接状态（T7 断线恢复判定用；null = 尚未观测）。 */
    private volatile Boolean lastConnected;

    /** 人工唤醒（T6）待下次扫描处理。 */
    private final ConcurrentLinkedQueue<TriggerHit> pendingManual = new ConcurrentLinkedQueue<>();
    /** 命中历史（最新在前）。 */
    private final Deque<TriggerHit> recentHits = new ArrayDeque<>();

    @Autowired
    public TriggerEngine(MarketWatcher watcher,
                         Mt5BridgeService bridge,
                         TradePlanMapper tradePlanMapper,
                         SessionManager sessionManager,
                         ScheduleProperties props,
                         List<TriggerRule> rules,
                         SystemStateService stateService,
                         AlertService alertService) {
        this(watcher, bridge, tradePlanMapper, sessionManager, props, rules, stateService, alertService,
                Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    TriggerEngine(MarketWatcher watcher,
                  Mt5BridgeService bridge,
                  TradePlanMapper tradePlanMapper,
                  SessionManager sessionManager,
                  ScheduleProperties props,
                  List<TriggerRule> rules,
                  SystemStateService stateService,
                  AlertService alertService,
                  Clock clock) {
        this.watcher = watcher;
        this.bridge = bridge;
        this.tradePlanMapper = tradePlanMapper;
        this.sessionManager = sessionManager;
        this.props = props;
        this.rules = rules == null ? List.of()
                : rules.stream().sorted(Comparator.comparing((TriggerRule r) -> r.source().name())
                        .thenComparing(TriggerRule::name)).toList();
        this.stateService = stateService;
        this.alertService = alertService;
        this.clock = clock;
    }

    /** 定时扫描（间隔由 {@code quanta.schedule.trigger.scan-interval-ms} 决定）。 */
    @Scheduled(fixedDelayString = "${quanta.schedule.trigger.scan-interval-ms:1000}")
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        // Token 预算降级（DEGRADED）→ 关闭全部触发（设计 §8.4；budget 层置位）
        if (degraded()) {
            return;
        }
        boolean connected = watcher.connected();
        handleReconnect(connected);      // T7
        handleManualWake();              // T6

        MarketSnapshot snapshot = watcher.snapshot();
        if (snapshot == null) {
            return;
        }
        TriggerContext ctx = new TriggerContext(snapshot, props);
        safe("T1", () -> evaluateT1(snapshot));
        safe("T2", () -> evaluateT2(snapshot));
        safe("T5", () -> evaluateT5(snapshot));
        safe("T3/T4", () -> evaluatePlugins(ctx));
    }

    /** 人工唤醒（T6）：登记后由下一次扫描分发；若总开关或 T6 开关关闭则忽略。 */
    public void manualWake(String reason) {
        if (!props.isEnabled() || !props.getTrigger().enabled(TriggerSource.T6)) {
            throw new BizException(ResultCode.BIZ_ERROR.code(),
                    "人工唤醒（T6）已被关闭，无法发起", 409);
        }
        String why = (reason == null || reason.isBlank()) ? "人工唤醒" : reason.trim();
        pendingManual.add(new TriggerHit(TriggerSource.T6, watcher.symbol(), why,
                null, null, null, clock.instant()));
    }

    /** 系统事件（T7）：供桥接恢复、熔断解除、会话关闭前等场景主动上报。 */
    public void systemEvent(String type, String reason) {
        dispatch(new TriggerHit(TriggerSource.T7, watcher.symbol(),
                (type == null ? "系统事件" : type) + (reason == null || reason.isBlank() ? "" : "：" + reason),
                null, null, null, clock.instant()));
    }

    /** 命中历史（最新在前，最多 {@value #HIT_HISTORY} 条）。 */
    public List<TriggerHit> recentHits() {
        synchronized (recentHits) {
            return new ArrayList<>(recentHits);
        }
    }

    // ==================== 各触发源判定 ====================

    /** T1 到预期位：未过期 PENDING 计划的目标价落在最新 K 线 [low, high] 内 → 命中并置 TRIGGERED。 */
    private void evaluateT1(MarketSnapshot snap) {
        if (!props.getTrigger().enabled(TriggerSource.T1)) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        List<TradePlan> plans = tradePlanMapper.listPendingUnexpired(now);
        if (plans == null || plans.isEmpty()) {
            return;
        }
        for (TradePlan plan : plans) {
            BigDecimal target = plan.getTargetPrice();
            if (target == null) {
                continue;
            }
            double price = target.doubleValue();
            if (price >= snap.low() - EPS && price <= snap.high() + EPS) {
                tradePlanMapper.updateStatus(plan.getId(), "TRIGGERED");
                dispatch(new TriggerHit(TriggerSource.T1, snap.symbol(),
                        "价格触及计划目标 " + price + "（区间 " + snap.low() + "~" + snap.high() + "）",
                        plan.getId(), null, snap.barTime(), clock.instant()));
            }
        }
    }

    /** T2 时间驱动：每 N 秒 / 每根 K 线收盘。 */
    private void evaluateT2(MarketSnapshot snap) {
        if (!props.getTrigger().enabled(TriggerSource.T2)) {
            return;
        }
        ScheduleProperties.Time cfg = props.getTrigger().getTime();
        Instant now = clock.instant();
        String reason = null;

        if (cfg.isBarClose() && snap.barTime() != null && !snap.barTime().equals(lastBarTime)) {
            lastBarTime = snap.barTime();
            reason = "新 K 线收盘 " + snap.barTime();
        }
        if (cfg.getIntervalSeconds() > 0) {
            Instant last = lastTimeFireAt;
            if (last == null || !now.isBefore(last.plusSeconds(cfg.getIntervalSeconds()))) {
                reason = (reason == null)
                        ? "时间驱动（每 " + cfg.getIntervalSeconds() + " 秒）"
                        : reason + " + 时间驱动";
            }
        }
        if (reason != null) {
            lastTimeFireAt = now;
            dispatch(new TriggerHit(TriggerSource.T2, snap.symbol(), reason,
                    null, null, snap.barTime(), now));
        }
    }

    /** T5 持仓事件：浮盈/浮亏达阈值。 */
    private void evaluateT5(MarketSnapshot snap) {
        if (!props.getTrigger().enabled(TriggerSource.T5)) {
            return;
        }
        ScheduleProperties.PositionEvent cfg = props.getTrigger().getPositionEvent();
        List<Position> positions = bridge.positions(snap.symbol());
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (Position pos : positions) {
            if (pos.profit() >= cfg.getProfitThreshold()) {
                dispatch(new TriggerHit(TriggerSource.T5, snap.symbol(),
                        "持仓 " + pos.ticket() + " 浮盈达阈值 " + pos.profit(),
                        null, pos.ticket(), snap.barTime(), clock.instant()));
            } else if (pos.profit() <= -cfg.getLossThreshold()) {
                dispatch(new TriggerHit(TriggerSource.T5, snap.symbol(),
                        "持仓 " + pos.ticket() + " 浮亏达阈值 " + pos.profit(),
                        null, pos.ticket(), snap.barTime(), clock.instant()));
            }
        }
    }

    /** T3/T4 插件：逐插件判定，命中即分发。 */
    private void evaluatePlugins(TriggerContext ctx) {
        for (TriggerRule rule : rules) {
            if (!props.getTrigger().enabled(rule.source())) {
                continue;
            }
            try {
                if (rule.matches(ctx)) {
                    dispatch(new TriggerHit(rule.source(), ctx.snapshot().symbol(),
                            rule.name() + " 命中", null, null, ctx.snapshot().barTime(), clock.instant()));
                }
            } catch (RuntimeException e) {
                log.warn("插件 {}（{}）判定异常：{}", rule.name(), rule.source(), e.getMessage());
            }
        }
    }

    /** T7 断线恢复/告警：观测连接跃迁（false↔true）即告警；false → true 同时命中 T7。 */
    private void handleReconnect(boolean connected) {
        if (lastConnected != null && !lastConnected && connected) {
            alertService.connectivityUp("symbol=" + watcher.symbol());
            dispatch(new TriggerHit(TriggerSource.T7, watcher.symbol(), "断线恢复",
                    null, null, null, clock.instant()));
        } else if (lastConnected != null && lastConnected && !connected) {
            alertService.connectivityDown("symbol=" + watcher.symbol()
                    + "，连续失败 " + watcher.consecutiveFailures() + " 次");
        }
        lastConnected = connected;
    }

    /** T6 人工唤醒：把登记项分发出去。 */
    private void handleManualWake() {
        TriggerHit hit;
        while ((hit = pendingManual.poll()) != null) {
            dispatch(hit);
        }
    }

    // ==================== 分发 ====================

    /** 命中分发：过独立开关 → 记历史 → 申请会话窗口。分发异常被隔离。 */
    private void dispatch(TriggerHit hit) {
        if (hit == null || !props.getTrigger().enabled(hit.source()) || degraded()) {
            return;
        }
        synchronized (recentHits) {
            recentHits.addFirst(hit);
            while (recentHits.size() > HIT_HISTORY) {
                recentHits.removeLast();
            }
        }
        try {
            SessionManager.AcquireResult result = sessionManager.acquire(hit.source(), hit.symbol(), hit.ticket(), null);
            log.info("触发命中 [{}] {} | {} | 会话分配：{}",
                    hit.source(), hit.symbol(), hit.reason(), describe(result));
        } catch (RuntimeException e) {
            log.error("触发命中后分配会话失败（{}）：{}", hit.source(), e.getMessage());
        }
    }

    private static String describe(SessionManager.AcquireResult r) {
        return switch (r.status()) {
            case ACQUIRED -> "新建会话 #" + r.lease().sessionNo();
            case REUSED -> "复用会话 #" + r.lease().sessionNo() + "（持仓级互斥）";
            case QUEUED -> "排队中（第 " + r.queuePosition() + " 位）";
        };
    }

    private static void safe(String source, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("触发源 {} 判定异常（已隔离）：{}", source, e.getMessage());
        }
    }

    /** 是否处于 Token 预算降级态；读取失败按「未降级」处理（fail-open，避免库抖动使盯盘整体停摆）。 */
    private boolean degraded() {
        try {
            return stateService.isDegraded();
        } catch (RuntimeException e) {
            log.warn("读取降级态失败，本次按未降级处理：{}", e.getMessage());
            return false;
        }
    }
}
