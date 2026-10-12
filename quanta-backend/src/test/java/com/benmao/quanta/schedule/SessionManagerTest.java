package com.benmao.quanta.schedule;

import com.benmao.quanta.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionManager 单测（M7 验收：max_session 可配 1~20、超限排队、持仓级互斥）。
 */
class SessionManagerTest {

    private ScheduleProperties props;
    private SessionManager manager;

    @BeforeEach
    void setUp() {
        props = new ScheduleProperties();
        manager = new SessionManager(props, new MutableClock(Instant.parse("2026-10-12T08:00:00Z")));
    }

    @Test
    @DisplayName("max_session 合法范围 1~20；越界直接拒绝并回执范围")
    void maxSessionBoundsValidated() {
        props.setMaxSession(0);
        BizException low = assertThrows(BizException.class, () -> manager.maxSession());
        assertTrue(low.getMessage().contains("[1, 20]"));

        props.setMaxSession(21);
        BizException high = assertThrows(BizException.class, () -> manager.maxSession());
        assertTrue(high.getMessage().contains("[1, 20]"));

        props.setMaxSession(1);
        assertEquals(1, manager.maxSession());
        props.setMaxSession(20);
        assertEquals(20, manager.maxSession());
    }

    @Test
    @DisplayName("窗口满时排队，释放后自动提升队首")
    void queuesWhenFullAndPromotesOnRelease() {
        props.setMaxSession(2);

        SessionManager.AcquireResult a1 = manager.acquire(TriggerSource.T1, "GOLD", null, null);
        SessionManager.AcquireResult a2 = manager.acquire(TriggerSource.T2, "GOLD", null, null);
        assertEquals(SessionManager.AcquireStatus.ACQUIRED, a1.status());
        assertEquals(SessionManager.AcquireStatus.ACQUIRED, a2.status());
        assertNotEquals(a1.lease().sessionNo(), a2.lease().sessionNo());

        SessionManager.AcquireResult a3 = manager.acquire(TriggerSource.T2, "GOLD", null, null);
        assertEquals(SessionManager.AcquireStatus.QUEUED, a3.status());
        assertEquals(1, a3.queuePosition());
        assertEquals(2, manager.activeCount());
        assertEquals(1, manager.queuedCount());

        SessionManager.Lease promoted = manager.release(a1.lease().sessionNo());
        assertNotNull(promoted);
        assertEquals(2, manager.activeCount());
        assertEquals(0, manager.queuedCount());
    }

    @Test
    @DisplayName("持仓级互斥：同一 ticket 只归属一个会话，再次申请复用同一窗口")
    void positionLevelMutexReusesSession() {
        props.setMaxSession(5);

        SessionManager.AcquireResult first = manager.acquire(TriggerSource.T1, "GOLD", 7001L, null);
        SessionManager.AcquireResult second = manager.acquire(TriggerSource.T5, "GOLD", 7001L, null);

        assertEquals(SessionManager.AcquireStatus.REUSED, second.status());
        assertEquals(first.lease().sessionNo(), second.lease().sessionNo());
        assertEquals(1, manager.activeCount(), "同一持仓不得占用第二个会话窗口");
        assertTrue(manager.isManagedBy(7001L, first.lease().sessionNo()));
    }

    @Test
    @DisplayName("触发来源映射：不同来源各占独立窗口")
    void distinctSourcesGetDistinctSessions() {
        props.setMaxSession(5);

        SessionManager.AcquireResult plan = manager.acquire(TriggerSource.T1, "GOLD", null, null);
        SessionManager.AcquireResult soft = manager.acquire(TriggerSource.T5, "GOLD", 8002L, null);

        assertNotEquals(plan.lease().sessionNo(), soft.lease().sessionNo());
        assertEquals(2, manager.activeCount());
    }

    @Test
    @DisplayName("释放后持仓绑定解除，可再次分配")
    void releaseUnbindsTicket() {
        props.setMaxSession(5);
        SessionManager.AcquireResult first = manager.acquire(TriggerSource.T1, "GOLD", 9001L, null);
        manager.release(first.lease().sessionNo());

        SessionManager.AcquireResult again = manager.acquire(TriggerSource.T5, "GOLD", 9001L, null);
        assertEquals(SessionManager.AcquireStatus.ACQUIRED, again.status());
    }
}
