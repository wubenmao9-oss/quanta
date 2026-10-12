package com.benmao.quanta.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用可变时钟：便于验证退避重试与时间驱动触发。 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    MutableClock(Instant now) {
        this(now, ZoneId.of("Asia/Shanghai"));
    }

    MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
