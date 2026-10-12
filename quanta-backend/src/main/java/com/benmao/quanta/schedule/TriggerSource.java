package com.benmao.quanta.schedule;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Arrays;
import java.util.Locale;

/**
 * 触发源编号（设计 §6.2 T1~T8）：决定「何时唤醒 AI」。
 *
 * <p>只有本枚举命中的触发才允许进入会话研判；<b>每个触发源一个独立开关</b>（见
 * {@link ScheduleProperties.Trigger#enabled(TriggerSource)}）。
 */
public enum TriggerSource {

    /** 到预期位：PLAN / SOFT_TP 目标价被触及。 */
    T1("到预期位"),
    /** 时间驱动：每 N 分钟 / 每根 K 线收盘。 */
    T2("时间驱动"),
    /** 形态（插件化）：先落「放量 + 吞没」。 */
    T3("形态"),
    /** 指标（插件化）：先落「RSI + 布林带」。 */
    T4("指标"),
    /** 持仓事件：软 SL/TP 命中、浮盈/浮亏达阈值。 */
    T5("持仓事件"),
    /** 人工唤醒：用户手动发起。 */
    T6("人工唤醒"),
    /** 系统事件：断线恢复、熔断解除、会话关闭前。 */
    T7("系统事件"),
    /** 数据发布（二期，一期不启用）。 */
    T8("数据发布");

    private final String label;

    TriggerSource(String label) {
        this.label = label;
    }

    /** 中文说明。 */
    public String label() {
        return label;
    }

    /** 解析编号（大小写不敏感）；非法值回执合法清单。 */
    public static TriggerSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "触发源不能为空，可选：" + names(), 400);
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.name().equals(key))
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.PARAM_ERROR.code(),
                        "非法触发源：" + raw + "，可选：" + names(), 400));
    }

    private static String names() {
        return Arrays.stream(values()).map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
