package com.benmao.quanta.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ok_shouldCarrySuccessCodeAndData() {
        Result<String> result = Result.ok("pong");
        assertEquals(ResultCode.SUCCESS.code(), result.getCode());
        assertEquals("pong", result.getData());
        assertEquals(ResultCode.SUCCESS.message(), result.getMessage());
    }

    @Test
    void ok_withoutData() {
        Result<Void> result = Result.ok();
        assertEquals(ResultCode.SUCCESS.code(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void fail_shouldCarryGivenCodeAndMessage() {
        Result<Void> result = Result.fail(ResultCode.PARAM_ERROR.code(), "参数错误");
        assertEquals(ResultCode.PARAM_ERROR.code(), result.getCode());
        assertEquals("参数错误", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void traceId_shouldBeReadFromMdc() {
        MDC.put("trace_id", "0123456789abcdef0123456789abcdef");
        Result<Void> result = Result.ok();
        assertEquals("0123456789abcdef0123456789abcdef", result.getTraceId());
    }
}