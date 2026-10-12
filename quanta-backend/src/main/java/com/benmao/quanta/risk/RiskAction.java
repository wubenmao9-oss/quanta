package com.benmao.quanta.risk;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Locale;

/** 风控要校验的动作类型（所有"写"操作的枚举）。 */
public enum RiskAction {

    OPEN,
    CLOSE,
    MODIFY;

    /** 解析动作：不区分大小写。非法值拒绝并回执可选值。 */
    public static RiskAction parse(Object raw) {
        if (raw == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "动作不能为空，可选：OPEN, CLOSE, MODIFY", 400);
        }
        String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        switch (s) {
            case "OPEN" -> {
                return OPEN;
            }
            case "CLOSE" -> {
                return CLOSE;
            }
            case "MODIFY" -> {
                return MODIFY;
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的动作：" + raw + "，可选：OPEN, CLOSE, MODIFY", 400);
        }
    }
}
