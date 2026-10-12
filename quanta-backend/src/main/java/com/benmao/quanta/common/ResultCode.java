package com.benmao.quanta.common;

/** 统一返回码。0 = 成功；非 0 为各类错误。 */
public enum ResultCode {

    SUCCESS(0, "成功"),
    PARAM_ERROR(40000, "参数错误"),
    NOT_FOUND(40400, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),
    BIZ_ERROR(50000, "业务异常"),
    SYSTEM_ERROR(50001, "系统异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}