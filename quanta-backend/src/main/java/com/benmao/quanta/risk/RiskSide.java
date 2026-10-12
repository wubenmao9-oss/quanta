package com.benmao.quanta.risk;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Locale;

/** 交易方向。 */
public enum RiskSide {

    BUY,
    SELL;

    /** 解析方向：支持 buy/sell（不区分大小写），以及 MT5 的 0/1。非法值拒绝并回执可选值。 */
    public static RiskSide parse(Object raw) {
        if (raw == null) {
            throw new BizException(ResultCode.PARAM_ERROR.code(), "方向不能为空，可选：BUY, SELL", 400);
        }
        String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        switch (s) {
            case "BUY", "0", "LONG" -> {
                return BUY;
            }
            case "SELL", "1", "SHORT" -> {
                return SELL;
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "不支持的方向：" + raw + "，可选：BUY, SELL", 400);
        }
    }
}
