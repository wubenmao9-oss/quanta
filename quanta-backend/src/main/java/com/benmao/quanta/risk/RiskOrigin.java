package com.benmao.quanta.risk;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

/**
 * 写操作发起来源（M13）：用于区分「AI 自主」与「人工手动」。
 *
 * <p>仅影响 R11 三态判定：{@code PAUSED}/{@code TAKEOVER} 只拦 {@link #AI}——
 * 人工操作本身就是"人工介入"，仍需穿过 R1~R10、R12~R15 全部硬限额，但不该被三态自我拦截。
 */
public enum RiskOrigin {

    /** AI 自主发起（默认）。 */
    AI,
    /** 人工经系统手动发起。 */
    MANUAL;

    /** 宽松解析：null/空 → {@link #AI}；不识别 → 抛参数异常。 */
    public static RiskOrigin parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AI;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "非法来源 origin：" + raw + "，可选 AI / MANUAL", 400);
        }
    }
}
