package com.benmao.quanta.config;

/**
 * 凭据掩码工具：对外回显一律脱敏（对齐设计 §15「sk-****」）。
 *
 * <p>规则：{@code null} → {@code null}；长度 ≤ 6 → {@code "****"}；
 * 否则保留前 3 位 + {@code "****"}（如 {@code sk-abc... → "sk-****"}）。
 */
public final class ConfigMasker {

    private static final String MASK = "****";
    private static final int VISIBLE_PREFIX = 3;
    private static final int SHORT_LENGTH = 6;

    private ConfigMasker() {
    }

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= SHORT_LENGTH) {
            return MASK;
        }
        return value.substring(0, VISIBLE_PREFIX) + MASK;
    }
}