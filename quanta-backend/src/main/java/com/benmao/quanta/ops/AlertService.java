package com.benmao.quanta.ops;

import com.benmao.quanta.notify.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键事件告警（M13，设计 §17「熔断/断线立即通知」）。
 *
 * <p>封装 {@link NotifyService} 发信（落 {@code notify_log}），并在邮件与外层逻辑间做<b>同类去抖</b>
 * （同一 subtype 在 {@code quanta.ops.alert-cooldown-seconds} 内只发一次），避免断线抖动/反复拒单导致告警风暴。
 *
 * <p>best-effort：任何异常只记日志，绝不影响主流程。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 断线（连接由通到断）。 */
    public static final String CONNECTIVITY_DOWN = "CONNECTIVITY_DOWN";
    /** 断线恢复（连接由断到通）。 */
    public static final String CONNECTIVITY_UP = "CONNECTIVITY_UP";
    /** 日亏熔断（R6）。 */
    public static final String CIRCUIT_BREAKER = "CIRCUIT_BREAKER";
    /** 对账发现差异。 */
    public static final String RECONCILE_DIFF = "RECONCILE_DIFF";
    /** 认领孤儿持仓。 */
    public static final String ORPHAN_CLAIMED = "ORPHAN_CLAIMED";

    private final NotifyService notifyService;
    private final OpsProperties props;
    private final Clock clock;

    /** subtype → 上次发送时刻（去抖用）。 */
    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    @Autowired
    public AlertService(NotifyService notifyService, OpsProperties props) {
        this(notifyService, props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    AlertService(NotifyService notifyService, OpsProperties props, Clock clock) {
        this.notifyService = notifyService;
        this.props = props;
        this.clock = clock;
    }

    public void connectivityDown(String detail) {
        alert(CONNECTIVITY_DOWN, "[Quanta] MT5 连接断开", "与 MT5 的连接已断开：" + safe(detail));
    }

    public void connectivityUp(String detail) {
        alert(CONNECTIVITY_UP, "[Quanta] MT5 连接恢复", "与 MT5 的连接已恢复：" + safe(detail));
    }

    public void circuitBreaker(String detail) {
        alert(CIRCUIT_BREAKER, "[Quanta] 日亏熔断触发", "当日亏损达到上限，已禁止开仓：" + safe(detail));
    }

    public void reconcileDiff(String detail) {
        alert(RECONCILE_DIFF, "[Quanta] 对账发现差异", "trade_order 与 MT5 成交历史不一致：" + safe(detail));
    }

    public void orphanClaimed(String detail) {
        alert(ORPHAN_CLAIMED, "[Quanta] 认领孤儿持仓", "发现无本地流水的持仓，已补建认领记录：" + safe(detail));
    }

    /**
     * 发送一条告警（同类去抖）。返回是否真正发送了邮件。
     *
     * @param subtype 告警子类型（去抖键）
     * @param subject 邮件主题
     * @param content 邮件正文
     */
    public boolean alert(String subtype, String subject, String content) {
        try {
            if (!props.isAlertEnabled()) {
                log.info("告警已关闭，跳过 [{}] {}", subtype, subject);
                return false;
            }
            if (suppressed(subtype)) {
                log.info("告警 [{}] 处于去抖窗口内，跳过", subtype);
                return false;
            }
            lastSentAt.put(subtype, clock.instant());
            return notifyService.email(props.getAlertNotifyType(), subject, content);
        } catch (RuntimeException e) {
            log.warn("告警发送失败（已忽略）：{}", e.getMessage());
            return false;
        }
    }

    private boolean suppressed(String subtype) {
        long cooldown = props.getAlertCooldownSeconds();
        if (cooldown <= 0) {
            return false;
        }
        Instant last = lastSentAt.get(subtype);
        return last != null && !clock.instant().isAfter(last.plusSeconds(cooldown));
    }

    private static String safe(String detail) {
        return detail == null ? "" : detail;
    }
}
