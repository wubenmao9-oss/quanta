package com.benmao.quanta.schedule;

import com.benmao.quanta.schedule.rule.RsiBollingerRule;
import com.benmao.quanta.schedule.rule.VolumeEngulfingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 结构性验收：调度层（盯盘 + 触发 + 会话窗口）<b>全程不依赖任何 LLM</b>。
 *
 * <p>通过反射检查调度层各类型的字段 / 构造参数 / 方法签名，任何引用 {@code dev.langchain4j} 的地方都会失败——
 * 这是「盯盘全程不调用 LLM（无 token 消耗）」的硬性保证。
 */
class ScheduleNoLlmDependencyTest {

    private static final String LLM_PACKAGE = "dev.langchain4j";

    private static final List<Class<?>> SCHEDULE_TYPES = List.of(
            MarketWatcher.class,
            TriggerEngine.class,
            SessionManager.class,
            ScheduleProperties.class,
            MarketSnapshot.class,
            TriggerContext.class,
            TriggerHit.class,
            TriggerRule.class,
            TriggerSource.class,
            ScheduleConfig.class,
            ScheduleController.class,
            VolumeEngulfingRule.class,
            RsiBollingerRule.class);

    @Test
    @DisplayName("调度层不引用任何 LLM 类型（盯盘 0 token 的结构性保证）")
    void noLlmDependency() {
        for (Class<?> type : SCHEDULE_TYPES) {
            for (Field field : type.getDeclaredFields()) {
                assertNotLlm(field.getType(), type, "字段 " + field.getName());
            }
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                for (Class<?> param : ctor.getParameterTypes()) {
                    assertNotLlm(param, type, "构造参数");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertNotLlm(method.getReturnType(), type, "方法返回 " + method.getName());
                for (Class<?> param : method.getParameterTypes()) {
                    assertNotLlm(param, type, "方法参数 " + method.getName());
                }
            }
        }
    }

    private static void assertNotLlm(Class<?> type, Class<?> owner, String where) {
        assertFalse(type.getName().startsWith(LLM_PACKAGE),
                owner.getSimpleName() + " 的 " + where + " 引用了 LLM 类型：" + type.getName());
    }
}
