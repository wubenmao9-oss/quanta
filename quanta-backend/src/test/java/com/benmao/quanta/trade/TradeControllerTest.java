package com.benmao.quanta.trade;

import com.benmao.quanta.common.GlobalExceptionHandler;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.ops.ManualTradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 交易接口单测（M6）：开仓/平仓/改仓入参装配 + 风控拒绝透传 + 非法方向被拒（M13 起人工入口走 ManualTradeService）。 */
class TradeControllerTest {

    private ManualTradeService manualTradeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        manualTradeService = mock(ManualTradeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TradeController(manualTradeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /open：入参正确装配为 OPEN 命令并回传流水")
    void openPassesThrough() throws Exception {
        when(manualTradeService.open(any())).thenReturn(order(1001L, 7001L, "FILLED"));

        mvc.perform(post("/api/trade/open").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"side\":\"buy\",\"volume\":0.01,"
                                + "\"sl\":4195.10,\"tp\":4210.10,\"sessionId\":7,\"planId\":9,\"traceId\":\"t1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FILLED"))
                .andExpect(jsonPath("$.data.ticket").value(7001));

        ArgumentCaptor<TradeCommand> captor = ArgumentCaptor.forClass(TradeCommand.class);
        verify(manualTradeService).open(captor.capture());
        TradeCommand cmd = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", cmd.action().name());
        org.junit.jupiter.api.Assertions.assertEquals("BUY", cmd.side().name());
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("0.01").compareTo(cmd.volume()));
    }

    @Test
    @DisplayName("POST /open：风控拒绝 → HTTP 400 且透传结构化原因")
    void openRejectedByRisk() throws Exception {
        when(manualTradeService.open(any())).thenThrow(new BizException(
                com.benmao.quanta.common.ResultCode.BIZ_ERROR.code(),
                "风控拒绝[R5]：开仓必须设置止损（SL 不能为空或 0）；合法边界 {minSlDistance=0.75}", 400));

        mvc.perform(post("/api/trade/open").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"side\":\"BUY\",\"volume\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("风控拒绝[R5]")))
                .andExpect(jsonPath("$.message", containsString("minSlDistance")));
    }

    @Test
    @DisplayName("POST /open：非法方向被拒并回执可选值（400）")
    void invalidSideRejected() throws Exception {
        mvc.perform(post("/api/trade/open").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"side\":\"HEDGE\",\"volume\":0.01}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("BUY, SELL")));
    }

    @Test
    @DisplayName("POST /close：按 ticket 平仓并回传 CLOSED 流水")
    void closePassesThrough() throws Exception {
        when(manualTradeService.close(any())).thenReturn(order(1002L, 7001L, "CLOSED"));

        mvc.perform(post("/api/trade/close").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"ticket\":7001,\"traceId\":\"t2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    @DisplayName("POST /modify：改 SL/TP 并回传流水")
    void modifyPassesThrough() throws Exception {
        when(manualTradeService.modify(any())).thenReturn(order(1003L, 7001L, "FILLED"));

        mvc.perform(post("/api/trade/modify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"GOLD\",\"ticket\":7001,\"sl\":4198.10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket").value(7001));
    }

    private static TradeOrder order(Long id, Long ticket, String status) {
        TradeOrder e = new TradeOrder();
        e.setId(id);
        e.setTicket(ticket);
        e.setSymbol("GOLD");
        e.setCmd("BUY");
        e.setVolume(new BigDecimal("0.01"));
        e.setStatus(status);
        e.setRetcode(10009);
        return e;
    }
}
