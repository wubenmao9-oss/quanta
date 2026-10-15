package com.benmao.quanta.risk;

/**
 * 风控档位（M14）：同一份 R1~R15 规则，按发起方使用不同的阈值集合。
 *
 * <ul>
 *   <li>{@link #DEFAULT}：单仓/AI 常规路径，阈值取 {@code risk_config} 首行（缺失回落 {@code quanta.risk.default-*}）。</li>
 *   <li>{@link #GRID}：多腿/马丁执行器专用档，<b>仅放宽</b> maxVolume / maxPositions / minRr
 *       （配置 {@code risk_config.grid_*}，缺失回落 {@code quanta.risk.default-grid-*}）；
 *       R1/R4/R5/R6/R7/R9/R10/R11/R13/R14 等其余规则<b>保持硬</b>（两档共用同列）。</li>
 * </ul>
 *
 * <p>默认 {@link #DEFAULT}，保证既有调用点（未传档位）行为完全不变。
 */
public enum RiskProfile {

    /** 常规档（默认）。 */
    DEFAULT,
    /** 多腿/马丁专用档。 */
    GRID;

    /** 宽松解析：null → {@link #DEFAULT}。 */
    public static RiskProfile orDefault(RiskProfile p) {
        return p == null ? DEFAULT : p;
    }
}
