package com.benmao.quanta.agent;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Agent 返回契约的动作枚举（设计 §3）。
 *
 * <table>
 *   <tr><td>{@code OPEN}</td><td>开仓（需 SL）</td></tr>
 *   <tr><td>{@code CLOSE}</td><td>平仓</td></tr>
 *   <tr><td>{@code MODIFY}</td><td>改 SL/TP</td></tr>
 *   <tr><td>{@code PLAN}</td><td>登记预期位，交 Java 盯盘</td></tr>
 *   <tr><td>{@code WAIT}</td><td>默认：不动作，等下一次唤醒</td></tr>
 *   <tr><td>{@code NOOP}</td><td>什么都不做，也不登记计划</td></tr>
 * </table>
 */
public enum AgentAction {

    /** 开仓（必须带 SL）。 */
    OPEN,
    /** 平仓。 */
    CLOSE,
    /** 修改 SL/TP。 */
    MODIFY,
    /** 登记预期位，交 Java 盯盘。 */
    PLAN,
    /** 默认：等下一次系统调用。 */
    WAIT,
    /** 什么都不做。 */
    NOOP;

    /** 是否为「写动作」（需要经过风控与券商执行）。 */
    public boolean isWrite() {
        return this == OPEN || this == CLOSE || this == MODIFY;
    }

    /** 可选值清单（用于错误回执，防幻觉）。 */
    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** 宽松解析：忽略大小写与首尾空白；非法值拒绝并回执合法枚举。 */
    public static AgentAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "缺少动作（action），可选：" + names(), 400);
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的动作：" + raw + "，可选：" + names(), 400);
        }
    }
}
