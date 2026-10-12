package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 券商可用品种清单（GET /symbols）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SymbolList(
        int count,
        @JsonProperty("default") String defaultSymbol,
        List<String> symbols
) {
}