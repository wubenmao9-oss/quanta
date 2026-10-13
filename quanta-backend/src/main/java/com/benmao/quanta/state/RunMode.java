package com.benmao.quanta.state;

import com.benmao.quanta.common.ResultCode;
import com.benmao.quanta.common.exception.BizException;

import java.util.Locale;

/**
 * 三态运行模式（设计 §8.1）：落 {@code sys_state.run_mode}，重启后保持。
 *
 * <ul>
 *   <li>{@link #AUTO}：AI 全自动执行（风控仍生效）；</li>
 *   <li>{@link #PAUSED}：暂停自动开仓，平仓/改仓仍允许；</li>
 *   <li>{@link #TAKEOVER}：人工接管，AI 只出建议，执行需人工确认。</li>
 * </ul>
 */
public enum RunMode {

    AUTO,
    PAUSED,
    TAKEOVER;

    /** 宽松解析：null/空 → {@link #AUTO}；不识别 → 抛参数异常（含合法取值提示）。 */
    public static RunMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_ERROR.code(),
                    "非法运行模式：" + raw + "，可选 AUTO / PAUSED / TAKEOVER", 400);
        }
    }
}
