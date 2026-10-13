package com.benmao.quanta.ops;

import com.benmao.quanta.notify.NotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 关键事件告警单测（M13）：走 notify_type、可关闭、同类去抖、best-effort。 */
class AlertServiceTest {

    private NotifyService notifyService;
    private OpsProperties props;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        props = new OpsProperties();
        when(notifyService.email(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("发送告警：走配置的 notify_type（默认 SYSTEM_ALERT）")
    void sendsWithConfiguredType() {
        new AlertService(notifyService, props).connectivityDown("断线");
        verify(notifyService).email(eq("SYSTEM_ALERT"), anyString(), anyString());
    }

    @Test
    @DisplayName("告警总开关关闭：不发信")
    void disabledSkips() {
        props.setAlertEnabled(false);
        new AlertService(notifyService, props).circuitBreaker("熔断");
        verify(notifyService, never()).email(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("同类去抖：冷却窗口内重复 subtype 只发一次，过窗口后再发")
    void debouncesWithinCooldown() {
        props.setAlertCooldownSeconds(300);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T08:00:00Z"));
        AlertService service = new AlertService(notifyService, props, clock);

        assertTrue(service.alert("CONNECTIVITY_DOWN", "s", "c"));
        assertFalse(service.alert("CONNECTIVITY_DOWN", "s", "c"), "冷却窗口内应被去抖");
        clock.advance(Duration.ofSeconds(301));
        assertTrue(service.alert("CONNECTIVITY_DOWN", "s", "c"), "过窗口后应再发");

        verify(notifyService, times(2)).email(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("best-effort：发信抛异常被吞掉，方法本身不抛")
    void swallowsFailure() {
        when(notifyService.email(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("smtp down"));
        AlertService service = new AlertService(notifyService, props);

        assertDoesNotThrow(() -> service.reconcileDiff("差异"));
    }

    /** 可手动推进的固定时钟（测试用）。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
