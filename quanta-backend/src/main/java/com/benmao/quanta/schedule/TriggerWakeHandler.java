package com.benmao.quanta.schedule;

/**
 * 触发命中后的「唤醒扩展点」（M14）。
 *
 * <p>调度层只依赖本接口——<b>不依赖任何 LLM 类型</b>（见 {@code ScheduleNoLlmDependencyTest}）：
 * 真正的 AI 研判实现放在 agent 包（{@code AgentTriggerWakeHandler}），由 Spring 注入为一组处理器。
 * 未提供任何实现、或自动唤醒开关关闭时，触发命中只分配会话、不唤醒 AI，保持一期「盯盘 0 token」的默认行为。
 */
public interface TriggerWakeHandler {

    /** 处理一次触发命中（会话已分配后调用）。实现需自行保证异常不外抛。 */
    void onTrigger(TriggerHit hit);
}
