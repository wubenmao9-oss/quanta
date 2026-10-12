package com.benmao.quanta.config;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

/** 配置类型：对应 sys_config.config_type。 */
public enum ConfigType {

    /** MT5 账户（账号/密码/服务器）。 */
    MT5,
    /** 大模型（接口类型/API 地址/模型名/密钥/日 Token 预算）。 */
    LLM,
    /** 邮箱（服务商/账号/IMAP/授权码/收件人）。 */
    MAIL;

    /** 宽松解析：忽略大小写与首尾空白；非法值抛参数错误。 */
    public static ConfigType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "configType 不能为空（可选 MT5/LLM/MAIL）");
        }
        try {
            return ConfigType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的配置类型: " + value + "（可选 MT5/LLM/MAIL）");
        }
    }
}