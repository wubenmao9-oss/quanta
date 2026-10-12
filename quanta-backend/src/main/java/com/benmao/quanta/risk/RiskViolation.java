package com.benmao.quanta.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条风控违规（结构化回执，供 AI 自我修正后重试）。
 *
 * @param rule       规则编号（R1~R15）
 * @param code       机器可读原因码（如 VOLUME_OUT_OF_RANGE）
 * @param message    人类可读原因
 * @param bounds     合法边界值（如 {"min":0.01,"max":0.01}），无则空表
 * @param suggestion 修正建议（如"把 volume 调到 [0.01, 0.01]"）
 */
public record RiskViolation(String rule,
                            String code,
                            String message,
                            Map<String, Object> bounds,
                            String suggestion) {

    public RiskViolation {
        // 允许边界值中出现 null（如"样本不足 → 中位数无值"），故不用 Map.copyOf（它拒绝 null 值）
        bounds = bounds == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(bounds));
    }

    /** 便捷构造：无边界值。 */
    public static RiskViolation of(String rule, String code, String message) {
        return new RiskViolation(rule, code, message, Map.of(), null);
    }

    /** 便捷构造：带边界值与建议。 */
    public static RiskViolation of(String rule, String code, String message,
                                   Map<String, Object> bounds, String suggestion) {
        return new RiskViolation(rule, code, message, bounds, suggestion);
    }

    /** 展开为有序 Map（回执用）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("code", code);
        m.put("message", message);
        if (!bounds.isEmpty()) {
            m.put("bounds", bounds);
        }
        if (suggestion != null) {
            m.put("suggestion", suggestion);
        }
        return m;
    }
}
