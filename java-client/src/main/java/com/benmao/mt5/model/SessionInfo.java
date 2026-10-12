package com.benmao.mt5.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 桥接会话状态：终端是否在线、当前登录账号。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionInfo(
        boolean ok,
        @JsonProperty("terminal_connected") Boolean terminalConnected,
        Long login,
        String server,
        String error
) {
}