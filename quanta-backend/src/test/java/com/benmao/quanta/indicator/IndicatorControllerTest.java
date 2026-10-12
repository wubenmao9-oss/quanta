package com.benmao.quanta.indicator;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IndicatorController 单测：真实调用接口、序列回显、非法参数 400 回执。 */
class IndicatorControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Mt5BridgeService bridge;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        IndicatorProperties props = new IndicatorProperties();
        IndicatorService service = new IndicatorService(new IndicatorParamValidator(props));
        bridge = mock(Mt5BridgeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new IndicatorController(service, bridge))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/indicator/compute：回显定型参数 + 序列 + 最新值")
    void computeReturnsSeries() throws Exception {
        mvc.perform(post("/api/indicator/compute").contentType(MediaType.APPLICATION_JSON)
                        .content(computeBody("RSI", Map.of("period", 14), bars(30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.indicator").value("RSI"))
                .andExpect(jsonPath("$.data.params.period").value(14))
                .andExpect(jsonPath("$.data.bars").value(30))
                .andExpect(jsonPath("$.data.series.value.length()").value(30))
                .andExpect(jsonPath("$.data.latest.value").isNumber());
    }

    @Test
    @DisplayName("非法参数 period=0 → 400，回执含合法边界 [2, 200]")
    void invalidPeriodReturns400WithBounds() throws Exception {
        mvc.perform(post("/api/indicator/compute").contentType(MediaType.APPLICATION_JSON)
                        .content(computeBody("RSI", Map.of("period", 0), bars(30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("[2, 200]")));
    }

    @Test
    @DisplayName("非整数参数 period=abc → 400")
    void nonIntegerPeriodReturns400() throws Exception {
        mvc.perform(post("/api/indicator/compute").contentType(MediaType.APPLICATION_JSON)
                        .content(computeBody("SMA", Map.of("period", "abc"), bars(30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("未知指标名 → 400，回执可选值")
    void unknownIndicatorReturns400() throws Exception {
        mvc.perform(post("/api/indicator/compute").contentType(MediaType.APPLICATION_JSON)
                        .content(computeBody("XYZ", Map.of(), bars(30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("可选")));
    }

    @Test
    @DisplayName("不带 klines 时从桥接层实时取数（默认品种 GOLD）")
    void computeFetchesKlinesFromBridge() throws Exception {
        when(bridge.defaultSymbol()).thenReturn("GOLD");
        when(bridge.klines("GOLD", "M5", 200)).thenReturn(bars(200));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("indicator", "EMA");
        body.put("params", Map.of("period", 10));
        mvc.perform(post("/api/indicator/compute").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bars").value(200));

        verify(bridge).klines("GOLD", "M5", 200);
    }

    @Test
    @DisplayName("POST /api/indicator/group：返回整体命中 + 逐条回执")
    void groupEndpoint() throws Exception {
        IndicatorGroupSpec spec = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("priceGt4200",
                        IndicatorGroupSpec.Operand.ofPrice(IndicatorGroupSpec.PriceField.CLOSE),
                        IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofConstant(4200)),
                new IndicatorGroupSpec.Rule("sma5Gt4200",
                        IndicatorGroupSpec.Operand.ofIndicator(new IndicatorGroupSpec.Ref(
                                IndicatorType.SMA, Map.of("period", 5), "value")),
                        IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofConstant(4200))
        ), IndicatorGroupSpec.Logic.AND);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group", spec);
        body.put("klines", bars(30));

        mvc.perform(post("/api/indicator/group").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.perRule.priceGt4200").value(true))
                .andExpect(jsonPath("$.data.perRule.sma5Gt4200").value(true));
    }

    // ---------------- 工具 ----------------

    private String computeBody(String indicator, Map<String, Object> params, List<KlineBar> klines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("indicator", indicator);
        body.put("params", params);
        body.put("klines", klines);
        return mapper.writeValueAsString(body);
    }

    private static List<KlineBar> bars(int count) {
        List<KlineBar> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double c = 4200 + i * 0.5;
            out.add(new KlineBar(1760000000L + i * 60L, c - 0.2, c + 0.5, c - 0.6, c, 100 + i));
        }
        return out;
    }
}