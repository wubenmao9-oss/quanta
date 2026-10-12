package com.benmao.quanta.schedule;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话窗口管理器（设计 §6.3 并发模型）。
 *
 * <p>职责：
 * <ul>
 *   <li>窗口数上限 {@code max_session}（合法 1~20），超限时按到达顺序<b>排队</b>，释放后自动提升；</li>
 *   <li><b>持仓级互斥</b>：同一 {@code ticket} 同一时刻只绑定一个会话——再次申请直接复用该会话；</li>
 *   <li><b>触发来源映射</b>：不带持仓的触发（如 T1 计划、T2 时间）各占独立窗口，带持仓的触发绑定到该持仓的窗口。</li>
 * </ul>
 *
 * <p>本类为策略型组件，窗口登记先落在内存（一期调度层尚不产生真实 AI 会话内容，M10 接入后由其登记 {@code agent_session}）。
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** {@code max_session} 合法下界。 */
    public static final int MIN_SESSION = 1;
    /** {@code max_session} 合法上界。 */
    public static final int MAX_SESSION = 20;

    private final ScheduleProperties props;
    private final Clock clock;
    private final Object lock = new Object();

    private int seq;
    /** session_no → 窗口。 */
    private final Map<Integer, SessionWindow> sessions = new LinkedHashMap<>();
    /** ticket → session_no（持仓级互斥）。 */
    private final Map<Long, Integer> byTicket = new LinkedHashMap<>();
    /** 超限时的等待队列（FIFO）。 */
    private final Deque<Pending> queue = new ArrayDeque<>();

    @Autowired
    public SessionManager(ScheduleProperties props) {
        this(props, Clock.systemDefaultZone());
    }

    /** 测试用：可注入固定时钟。 */
    SessionManager(ScheduleProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * 为一次触发申请会话窗口。
     *
     * @param source     触发源
     * @param symbol     品种
     * @param ticket     关联持仓；非空时参与持仓级互斥
     * @param strategyId 策略 id（可空）
     */
    public AcquireResult acquire(TriggerSource source, String symbol, Long ticket, Long strategyId) {
        int max = maxSession();
        synchronized (lock) {
            if (ticket != null) {
                Integer existing = byTicket.get(ticket);
                if (existing != null) {
                    return new AcquireResult(AcquireStatus.REUSED, leaseOf(existing), 0);
                }
            }
            if (sessions.size() >= max) {
                queue.addLast(new Pending(source, symbol, ticket, strategyId));
                log.info("会话窗口已满（{}/{}），触发 {} 进入排队，位置 {}", sessions.size(), max, source, queue.size());
                return new AcquireResult(AcquireStatus.QUEUED, null, queue.size());
            }
            return new AcquireResult(AcquireStatus.ACQUIRED, allocate(source, symbol, ticket, strategyId), 0);
        }
    }

    /** 释放某会话窗口；若有排队者，自动提升队首并返回其租约，否则返回 null。 */
    public Lease release(int sessionNo) {
        synchronized (lock) {
            SessionWindow window = sessions.remove(sessionNo);
            if (window == null) {
                return null;
            }
            if (window.ticket() != null) {
                byTicket.remove(window.ticket());
            }
            Pending next = queue.pollFirst();
            if (next == null) {
                return null;
            }
            Lease promoted = allocate(next.source(), next.symbol(), next.ticket(), next.strategyId());
            log.info("会话窗口释放，排队者已提升：sessionNo={}", promoted.sessionNo());
            return promoted;
        }
    }

    /** 生效的 {@code max_session}；越界直接抛错并回执合法范围。 */
    public int maxSession() {
        int max = props.getMaxSession();
        if (max < MIN_SESSION || max > MAX_SESSION) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "max_session 非法：" + max + "，合法范围 [" + MIN_SESSION + ", " + MAX_SESSION + "]", 400);
        }
        return max;
    }

    /** 当前活跃窗口数。 */
    public int activeCount() {
        synchronized (lock) {
            return sessions.size();
        }
    }

    /** 当前排队数。 */
    public int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** 活跃窗口租约快照（仅供观测）。 */
    public List<Lease> activeLeases() {
        synchronized (lock) {
            List<Lease> out = new ArrayList<>(sessions.size());
            for (SessionWindow w : sessions.values()) {
                out.add(new Lease(w.sessionNo(), w.symbol(), w.ticket(), w.source(), false));
            }
            return out;
        }
    }

    /** 持仓是否由指定会话接管。 */
    public boolean isManagedBy(Long ticket, int sessionNo) {
        synchronized (lock) {
            Integer held = byTicket.get(ticket);
            return held != null && held == sessionNo;
        }
    }

    private Lease allocate(TriggerSource source, String symbol, Long ticket, Long strategyId) {
        int sessionNo = ++seq;
        SessionWindow window = new SessionWindow(sessionNo, symbol, source, ticket, strategyId, clock.instant());
        sessions.put(sessionNo, window);
        if (ticket != null) {
            byTicket.put(ticket, sessionNo);
        }
        return new Lease(sessionNo, symbol, ticket, source, false);
    }

    private Lease leaseOf(int sessionNo) {
        SessionWindow w = sessions.get(sessionNo);
        return new Lease(w.sessionNo(), w.symbol(), w.ticket(), w.source(), true);
    }

    /** 申请结果状态。 */
    public enum AcquireStatus {
        /** 新分配窗口。 */
        ACQUIRED,
        /** 复用该持仓已绑定的窗口（持仓级互斥）。 */
        REUSED,
        /** 窗口已满，进入排队。 */
        QUEUED
    }

    /**
     * 会话窗口租约。
     *
     * @param sessionNo 动态窗口号
     * @param symbol    品种
     * @param ticket    绑定持仓（可空）
     * @param source    触发来源
     * @param reused    是否为复用已有窗口
     */
    public record Lease(int sessionNo, String symbol, Long ticket, TriggerSource source, boolean reused) {
    }

    /**
     * 申请结果。
     *
     * @param status        ACQUIRED / REUSED / QUEUED
     * @param lease         租约；QUEUED 时为 null
     * @param queuePosition 排队位置；未排队为 0
     */
    public record AcquireResult(AcquireStatus status, Lease lease, int queuePosition) {
    }

    private record SessionWindow(int sessionNo,
                                 String symbol,
                                 TriggerSource source,
                                 Long ticket,
                                 Long strategyId,
                                 Instant createTime) {
    }

    private record Pending(TriggerSource source, String symbol, Long ticket, Long strategyId) {
    }
}
