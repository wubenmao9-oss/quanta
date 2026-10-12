package com.benmao.mt5;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 返回码归一化：失败原因必须可读、未知码有兜底。 */
class Mt5ErrorCodeTest {

    @Test
    @DisplayName("已知返回码翻译成可读原因：10018 休市 / 10027 算法交易未开")
    void knownCodesAreReadable() {
        assertEquals(Mt5ErrorCode.MARKET_CLOSED, Mt5ErrorCode.from(10018));
        assertTrue(Mt5ErrorCode.explain(10018).contains("休市"));
        assertTrue(Mt5ErrorCode.explain(10027).contains("算法交易"));
        assertEquals("成功", Mt5ErrorCode.explain(10009));
    }

    @Test
    @DisplayName("未知返回码给出兜底文案且不丢原始码")
    void unknownCodeFallback() {
        assertNull(Mt5ErrorCode.from(99999));
        assertTrue(Mt5ErrorCode.explain(99999).contains("99999"));
    }

    @Test
    @DisplayName("null 表示请求未送达")
    void nullCodeMeansNotDelivered() {
        assertTrue(Mt5ErrorCode.explain(null).contains("未送达"));
    }

    @Test
    @DisplayName("成功判定只认 10009")
    void successOnlyFor10009() {
        assertTrue(Mt5ErrorCode.isSuccess(10009));
        assertFalse(Mt5ErrorCode.isSuccess(10008));
        assertFalse(Mt5ErrorCode.isSuccess(null));
    }
}