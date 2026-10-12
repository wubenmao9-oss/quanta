package com.benmao.quanta.config.tester;

import com.benmao.quanta.config.ConfigType;
import com.benmao.quanta.config.dto.ConfigTestResult;

import java.util.Map;

/**
 * 连接测试器：每类配置一个实现，按 {@link #type()} 分派。
 *
 * <p>{@code payload} 是 Service 从请求 DTO 抽取出的「字段名 → 值」映射（不含 name）。
 * 实现**不得抛异常**，一律以 {@link ConfigTestResult} 返回成功/失败与可读原因。
 */
public interface ConnectionTester {

    ConfigType type();

    ConfigTestResult test(Map<String, Object> payload);
}