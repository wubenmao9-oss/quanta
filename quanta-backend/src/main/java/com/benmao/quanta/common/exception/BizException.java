package com.benmao.quanta.common.exception;

import com.benmao.quanta.common.ResultCode;

/**
 * 业务异常：由业务代码主动抛出，携带业务错误码与对应的 HTTP 状态码。
 *
 * <p>默认 HTTP 状态码为 400；如需返回其他状态码，使用三参构造。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BizException(String message) {
        this(ResultCode.BIZ_ERROR.code(), message, 400);
    }

    public BizException(int code, String message) {
        this(code, message, 400);
    }

    public BizException(int code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}