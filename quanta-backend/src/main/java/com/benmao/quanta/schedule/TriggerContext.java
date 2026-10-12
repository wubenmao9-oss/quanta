package com.benmao.quanta.schedule;

/**
 * 触发插件（T3 形态 / T4 指标）的求值上下文。
 *
 * <p>插件只读行情与配置，返回是否命中；不产生任何副作用。
 *
 * @param snapshot 当前盯盘快照（含 K 线序列）
 * @param props    调度层配置（插件阈值来源）
 */
public record TriggerContext(MarketSnapshot snapshot, ScheduleProperties props) {
}
