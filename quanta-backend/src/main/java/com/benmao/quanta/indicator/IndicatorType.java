package com.benmao.quanta.indicator;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 一期支持的指标（设计 §10）：基础 7 个（EMA/SMA/RSI/MACD/BOLL/ATR/成交量）
 * + 多样化扩展 14 个（ADX/Aroon/Stoch/CCI/WILLR/ROC/Keltner/Donchian/BBW/%B/StdDev/Choppiness/Supertrend/ADR）。
 *
 * <p>扩展指标全部<b>只消费 OHLCV</b>、<b>不重绘</b>、<b>可参数化</b>：
 * <ul>
 *   <li>排除量价类（GOLD 是 tick volume，失真）；</li>
 *   <li>排除重绘/未来函数类（ZigZag / Nadaraya-Watson / MESA 等）；</li>
 *   <li>排除数据不可得类（CVD / TPO / VPVR / 筹码分布 等）。</li>
 * </ul>
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
    VOLUME("volume"),
    ADX("adx"),
    AROON("osc"),
    STOCH("k"),
    CCI("value"),
    WILLR("value"),
    ROC("value"),
    KELTNER("mid"),
    DONCHIAN("mid"),
    BBW("value"),
    BBP("value"),
    STDDEV("value"),
    CHOP("value"),
    SUPERTREND("line"),
    ADR("value");

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