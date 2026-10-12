package com.benmao.quanta.common;

import org.slf4j.MDC;

/**
 * 统一返回体。
 *
 * <p>{@code traceId} 自动取自 MDC（由 {@code TraceIdFilter} 写入），
 * 便于把 HTTP 响应与日志用同一个 trace_id 串起来。
 */
public class Result<T> {

    public static final String TRACE_ID_KEY = "trace_id";

    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp;

    public Result() {
        this.traceId = MDC.get(TRACE_ID_KEY);
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String message, T data) {
        this();
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.code(), resultCode.message(), null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}