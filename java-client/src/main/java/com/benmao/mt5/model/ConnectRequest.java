package com.benmao.mt5.model;

/** 登录请求：用指定凭据初始化 MT5 终端。字段为 null 时桥接服务回落到自身 .env 配置。 */
public record ConnectRequest(Integer login, String password, String server, String path) {
}