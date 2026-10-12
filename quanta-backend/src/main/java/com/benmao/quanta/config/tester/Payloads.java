package com.benmao.quanta.config.tester;

import java.util.List;
import java.util.Map;

/** 从 payload Map 中安全取值的小工具（宽容类型：Number/String/Boolean/List）。 */
final class Payloads {

    private Payloads() {
    }

    static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    static Integer intOf(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 空/缺失时取 default。 */
    static boolean boolOf(Map<String, Object> p, String key, boolean defaultValue) {
        Object v = p.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    @SuppressWarnings("unchecked")
    static List<String> listOf(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}