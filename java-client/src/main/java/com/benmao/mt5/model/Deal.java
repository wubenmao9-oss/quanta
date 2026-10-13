package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MT5 成交历史（deal）。用于对账：与本地 {@code trade_order} 按 {@code positionId}（持仓 ticket）对齐。
 *
 * @param entry 成交方向：0=IN 开仓、1=OUT 平仓、2=INOUT、3=OUT_BY
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Deal(
        long ticket,
        long order,
        @JsonProperty("position_id") long positionId,
        String symbol,
        String type,
        int entry,
        double volume,
        double price,
        double profit,
        double swap,
        double commission,
        String time,
        String comment
) {
}
