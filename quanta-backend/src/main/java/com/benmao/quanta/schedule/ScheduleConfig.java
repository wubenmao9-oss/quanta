package com.benmao.quanta.schedule;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度层开关：启用 Spring 定时任务，驱动 {@link MarketWatcher} 与 {@link TriggerEngine}。
 *
 * <p>是否真正工作仍由 {@code quanta.schedule.enabled} / 各子开关决定。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}
