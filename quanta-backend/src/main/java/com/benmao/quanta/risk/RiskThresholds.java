package com.benmao.quanta.risk;

import java.math.BigDecimal;
import java.util.List;

/**
 * 生效中的风控阈值（来源：{@code risk_config} 表，人工可改；表为空时用 {@link RiskProperties} 兜底默认）。
 */
public record RiskThresholds(BigDecimal fixedVolume,
                             BigDecimal maxVolume,
                             int maxPositions,
                             BigDecimal maxDailyLoss,
                             BigDecimal maxRiskPct,
                             BigDecimal minRr,
                             BigDecimal minSlSpreadMult,
                             int maxConsecutiveLoss,
                             int cooldownMinutes,
                             BigDecimal spreadLimit,
                             List<String> symbolWhitelist) {

    public RiskThresholds {
        symbolWhitelist = symbolWhitelist == null ? List.of() : List.copyOf(symbolWhitelist);
    }
}
