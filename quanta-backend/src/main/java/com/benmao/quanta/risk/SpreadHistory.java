package com.benmao.quanta.risk;

import java.time.LocalDateTime;

/**
 * 点差历史（R9 动态点差阈值用）。
 *
 * <p>M5 由 {@link RiskGuard} 在每次校验时采样喂入；M7 盯盘进程接管高频采样。
 */
public interface SpreadHistory {

    /** 记一次点差采样（单位：points，与 MT5 {@code symbol_info.spread} 一致）。 */
    void record(double spreadPoints, LocalDateTime at);

    /** 统计最近 {@code windowMinutes} 分钟的点差。 */
    Stat stat(LocalDateTime now, int windowMinutes);

    /**
     * 点差统计结果。
     *
     * @param samples     窗口内样本数
     * @param medianPoints 中位数（points）；样本为 0 时为 NaN
     */
    record Stat(int samples, double medianPoints) {
    }
}
