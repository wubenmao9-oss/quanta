package com.benmao.quanta.schedule;

/**
 * 触发规则插件接口（设计 §6.2：T3/T4 做成「规则插件」——加新形态/新指标只需加插件，不动主干）。
 *
 * <p>实现类声明为 Spring 组件即被 {@link TriggerEngine} 自动纳入；是否启用仍由
 * {@link ScheduleProperties.Trigger#enabled(TriggerSource)} 的独立开关决定。
 */
public interface TriggerRule {

    /** 归属的触发源（T3 / T4）。 */
    TriggerSource source();

    /** 规则名（用于命中原因展示）。 */
    String name();

    /** 是否命中；纯只读判断，无副作用。 */
    boolean matches(TriggerContext ctx);
}
