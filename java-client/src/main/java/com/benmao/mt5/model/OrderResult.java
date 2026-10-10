package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 下单/平仓/改仓的统一返回结果。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderResult(
        boolean success,
        Integer retcode,
        Long order,
        Long deal,
        Double volume,
        Double price,
        String comment,
        String error,
        @JsonProperty("closed_ticket") Long closedTicket,
        Double profit
) {
    @Override
    public String toString() {
        if (success) {
            return "OrderResult{success=true, order=%s, deal=%s, price=%s, volume=%s, comment='%s'}"
                    .formatted(order, deal, price, volume, comment);
        }
        return "OrderResult{success=false, retcode=%s, error='%s'}".formatted(retcode, error);
    }
}