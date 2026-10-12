package com.benmao.quanta.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ConfigMasker 单元测试：掩码规则（对齐 §15「sk-****」）。 */
class ConfigMaskerTest {

    @Test
    void null_returnsNull() {
        assertNull(ConfigMasker.mask(null));
    }

    @Test
    void shortValue_fullyMasked() {
        assertEquals("****", ConfigMasker.mask("abc"));
        assertEquals("****", ConfigMasker.mask("abcdef"));
    }

    @Test
    void longValue_keepsPrefixThenMask() {
        assertEquals("sk-****", ConfigMasker.mask("sk-1234567890"));
        assertEquals("abc****", ConfigMasker.mask("abc123456"));
    }
}