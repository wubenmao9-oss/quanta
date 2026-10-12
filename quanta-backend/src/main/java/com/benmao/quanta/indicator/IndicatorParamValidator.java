package com.benmao.quanta.indicator;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标参数边界校验（设计 §10 防幻觉）：{@code period ∈ [periodMin, periodMax]} 且必须为整数，
 * 非法即拒，并**回执合法边界值**；MACD 额外要求 {@code fast < slow}。
 */
@Component
public class IndicatorParamValidator {

    private final IndicatorProperties props;

    public IndicatorParamValidator(IndicatorProperties props) {
        this.props = props;
    }

    /** 把任意来源（AI/JSON/表单）的参数解析成该指标所需的定型参数表。 */
    public Map<String, Object> resolved(IndicatorType type, Map<String, Object> params) {
        Map<String, Object> in = params == null ? Map.of() : params;
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case SMA -> out.put("period", period(type, in, "period", props.getSmaPeriod()));
            case EMA -> out.put("period", period(type, in, "period", props.getEmaPeriod()));
            case RSI -> out.put("period", period(type, in, "period", props.getRsiPeriod()));
            case ATR -> out.put("period", period(type, in, "period", props.getAtrPeriod()));
            case VOLUME -> out.put("period", period(type, in, "period", props.getVolumeMaPeriod()));
            case BOLL -> {
                out.put("period", period(type, in, "period", props.getBollPeriod()));
                out.put("k", bollK(type, in, "k", props.getBollK()));
            }
            case MACD -> {
                int fast = period(type, in, "fast", props.getMacdFast());
                int slow = period(type, in, "slow", props.getMacdSlow());
                int signal = period(type, in, "signal", props.getMacdSignal());
                if (fast >= slow) {
                    throw reject(type, "fast=" + fast + " 必须小于 slow=" + slow,
                            "合法范围 [" + props.getPeriodMin() + ", " + props.getPeriodMax() + "]");
                }
                out.put("fast", fast);
                out.put("slow", slow);
                out.put("signal", signal);
            }
        }
        return out;
    }

    /** 解析并校验整数周期参数；缺失则取默认值。 */
    public int period(IndicatorType type, Map<String, Object> params, String key, int defaultValue) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        int value = toInt(type, key, raw);
        if (value < props.getPeriodMin() || value > props.getPeriodMax()) {
            throw reject(type, key + "=" + raw,
                    "合法范围 [" + props.getPeriodMin() + ", " + props.getPeriodMax() + "]（必须为整数）");
        }
        return value;
    }

    /** 解析并校验 BOLL 标准差系数（可为小数）。 */
    public double bollK(IndicatorType type, Map<String, Object> params, String key, double defaultValue) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw reject(type, key + "=" + raw, "必须为数值");
            }
        }
        if (value < props.getBollKMin() || value > props.getBollKMax()) {
            throw reject(type, key + "=" + raw,
                    "合法范围 [" + props.getBollKMin() + ", " + props.getBollKMax() + "]");
        }
        return value;
    }

    private int toInt(IndicatorType type, String key, Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                throw reject(type, key + "=" + raw, "必须为整数");
            }
            return (int) d;
        }
        try {
            double d = Double.parseDouble(String.valueOf(raw).trim());
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                throw reject(type, key + "=" + raw, "必须为整数");
            }
            return (int) d;
        } catch (NumberFormatException e) {
            throw reject(type, key + "=" + raw, "必须为整数");
        }
    }

    private BizException reject(IndicatorType type, String what, String hint) {
        return new BizException(ResultCode.PARAM_ERROR.code(),
                "指标 " + type + " 参数非法：" + what + " " + hint, 400);
    }
}