package com.benmao.quanta.config.dto;

import java.util.Map;

/** 连接测试结果：不落库，供「先测后存」与错误回执使用。 */
public class ConfigTestResult {

    private boolean success;
    private String message;
    private long latencyMs;
    /** 附加信息（如健康检查返回的登录号/服务器）。 */
    private Map<String, Object> detail;

    public ConfigTestResult() {
    }

    public ConfigTestResult(boolean success, String message, long latencyMs, Map<String, Object> detail) {
        this.success = success;
        this.message = message;
        this.latencyMs = latencyMs;
        this.detail = detail;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }
}