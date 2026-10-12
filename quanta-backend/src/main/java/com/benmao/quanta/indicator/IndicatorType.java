package com.benmao.quanta.indicator;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 一期支持的指标（设计 §10）：EMA / SMA / RSI / MACD / BOLL / ATR / 成交量。
 *
 * <p>每个指标声明其默认输出字段（"指标组"里不指定 field 时用它）。
 */
public enum IndicatorType {

    SMA("value"),
    EMA("value"),
    RSI("value"),
    MACD("dif"),
    BOLL("mid"),
    ATR("value"),
    VOLUME("volume");

    private final String defaultField;

    IndicatorType(String defaultField) {
        this.defaultField = defaultField;
    }

    /** 默认输出字段名。 */
    public String defaultField() {
        return defaultField;
    }

    /** 可选值清单（用于错误回执，防幻觉）。 */
    public static String names() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** 宽松解析：大小写不敏感；非法值给出可读回执。 */
    public static IndicatorType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "缺少指标名，可选：" + names(), 400);
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的指标：" + raw + "，可选：" + names(), 400);
        }
    }
}