package com.benmao.quanta.indicator;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多样化扩展指标单测（14 个）：与独立参考实现（numpy 生成的 reference-extra.json）逐点比对，
 * 并覆盖「可传参」与「非法参数被拒并回执合法边界」。
 */
class IndicatorExtensionTest {

    private static final double TOL = 1e-6;

    private final IndicatorProperties props = new IndicatorProperties();
    private final IndicatorService service = new IndicatorService(new IndicatorParamValidator(props));

    // ---------------- ① 与独立参考实现逐点比对 ----------------

    @Test
    @DisplayName("① 14 个扩展指标与独立参考实现逐点误差在阈值内")
    void matchesExtensionReference() throws Exception {
        JsonNode expected = resource("reference-extra.json").get("expected");
        List<KlineBar> bars = barsFromReference();
        double[] closes = closes(bars);

        IndicatorService.AdxResult adx = service.adx(bars, 14);
        assertClose("ADX.plusDi", expected.get("adxPlusDi"), adx.plusDi());
        assertClose("ADX.minusDi", expected.get("adxMinusDi"), adx.minusDi());
        assertClose("ADX.adx", expected.get("adx"), adx.adx());

        IndicatorService.AroonResult aroon = service.aroon(bars, 14);
        assertClose("AROON.up", expected.get("aroonUp"), aroon.up());
        assertClose("AROON.down", expected.get("aroonDown"), aroon.down());
        assertClose("AROON.osc", expected.get("aroonOsc"), aroon.osc());

        IndicatorService.StochResult stoch = service.stoch(bars, 9, 3, 3);
        assertClose("STOCH.k", expected.get("stochK"), stoch.k());
        assertClose("STOCH.d", expected.get("stochD"), stoch.d());
        assertClose("STOCH.j", expected.get("stochJ"), stoch.j());

        assertClose("CCI", expected.get("cci"), service.cci(bars, 20));
        assertClose("WILLR", expected.get("willr"), service.willr(bars, 14));
        assertClose("ROC", expected.get("roc"), service.roc(closes, 10));
        assertClose("STDDEV", expected.get("stddev"), service.stddev(closes, 20));
        assertClose("ADR", expected.get("adr"), service.adr(bars, 14));
        assertClose("CHOP", expected.get("chop"), service.choppiness(bars, 14));

        IndicatorService.KeltnerResult keltner = service.keltner(bars, 20, 2.0);
        assertClose("KELTNER.mid", expected.get("keltnerMid"), keltner.mid());
        assertClose("KELTNER.upper", expected.get("keltnerUpper"), keltner.upper());
        assertClose("KELTNER.lower", expected.get("keltnerLower"), keltner.lower());

        IndicatorService.DonchianResult donchian = service.donchian(bars, 20);
        assertClose("DONCHIAN.upper", expected.get("donchianUpper"), donchian.upper());
        assertClose("DONCHIAN.mid", expected.get("donchianMid"), donchian.mid());
        assertClose("DONCHIAN.lower", expected.get("donchianLower"), donchian.lower());

        assertClose("BBW", expected.get("bbw"), service.bbWidth(closes, 20, 2.0));
        assertClose("BBP", expected.get("bbp"), service.bbPercent(closes, 20, 2.0));

        IndicatorService.SupertrendResult st = service.supertrend(bars, 10, 3.0);
        assertClose("SUPERTREND.line", expected.get("supertrendLine"), st.line());
        assertClose("SUPERTREND.direction", expected.get("supertrendDirection"), st.direction());
    }

    @Test
    @DisplayName("① 手工样例：Aroon / WILLR / ADR 可用纸笔核算")
    void handCheckedExtensionSamples() {
        // 严格单边上涨：最高价恒在最后一根 → Aroon Up=100；最低价恒在第一根 → Aroon Down=0
        List<KlineBar> up = bars(new double[]{10, 11, 12, 13, 14}, new double[]{9, 10, 11, 12, 13});
        IndicatorService.AroonResult a = service.aroon(up, 4);
        assertEquals(100.0, a.up()[4], TOL);
        assertEquals(0.0, a.down()[4], TOL);
        assertEquals(100.0, a.osc()[4], TOL);

        // WILLR：窗口内 HH=14、LL=9、close=11.5 → −100×(14−11.5)/(14−9)=−50
        List<KlineBar> w = new ArrayList<>();
        w.add(new KlineBar(1L, 10, 14, 9, 11.5, 1));
        assertEquals(-50.0, service.willr(w, 1)[0], TOL);

        // ADR：2 根，区间分别为 3 与 3 → 均值 3
        List<KlineBar> mid = bars(new double[]{11.5, 11.5}, new double[]{9, 9});
        assertEquals(3.0, service.adr(mid, 2)[1], TOL);
    }

    // ---------------- ② 可传参 ----------------

    @Test
    @DisplayName("② 参数生效：周期的不同会改变预热位置与结果")
    void periodsAreRespected() throws Exception {
        List<KlineBar> bars = barsFromReference();

        IndicatorService.AroonResult a14 = service.aroon(bars, 14);
        IndicatorService.AroonResult a7 = service.aroon(bars, 7);
        assertTrue(Double.isNaN(a14.osc()[13]) && !Double.isNaN(a14.osc()[14]), "Aroon(14) 首个有效值在索引 14");
        assertTrue(Double.isNaN(a7.osc()[6]) && !Double.isNaN(a7.osc()[7]), "Aroon(7) 首个有效值在索引 7");

        double[] cci20 = service.cci(bars, 20);
        double[] cci10 = service.cci(bars, 10);
        assertTrue(Double.isNaN(cci20[18]) && !Double.isNaN(cci20[19]));
        assertTrue(Double.isNaN(cci10[8]) && !Double.isNaN(cci10[9]));
        assertNotEquals(cci20[79], cci10[79], "周期不同结果应不同");

        double[] roc10 = service.roc(closes(bars), 10);
        double[] roc5 = service.roc(closes(bars), 5);
        assertTrue(Double.isNaN(roc10[9]) && !Double.isNaN(roc10[10]));
        assertTrue(Double.isNaN(roc5[4]) && !Double.isNaN(roc5[5]));
    }

    @Test
    @DisplayName("② 系数生效：Supertrend / Keltner 的 ATR 倍数越大通道越宽")
    void multiplierIsRespected() throws Exception {
        List<KlineBar> bars = barsFromReference();
        IndicatorService.SupertrendResult k3 = service.supertrend(bars, 10, 3.0);
        IndicatorService.SupertrendResult k1 = service.supertrend(bars, 10, 1.0);
        // 同一根：不同倍数得到的趋势线不应完全相同
        assertNotEquals(k3.line()[79], k1.line()[79]);

        IndicatorService.KeltnerResult ck2 = service.keltner(bars, 20, 2.0);
        IndicatorService.KeltnerResult ck1 = service.keltner(bars, 20, 1.0);
        assertTrue(ck2.upper()[79] - ck2.lower()[79] > ck1.upper()[79] - ck1.lower()[79],
                "倍数越大带宽越宽");
    }

    @Test
    @DisplayName("② resolvedParams 回显新指标默认值")
    void resolvedParamsEchoDefaults() {
        assertEquals(14, service.resolvedParams(IndicatorType.ADX, Map.of()).get("period"));
        assertEquals(9, service.resolvedParams(IndicatorType.STOCH, Map.of()).get("period"));
        assertEquals(3, service.resolvedParams(IndicatorType.STOCH, Map.of()).get("kSmooth"));
        assertEquals(2.0, service.resolvedParams(IndicatorType.KELTNER, Map.of()).get("k"));
        assertEquals(10, service.resolvedParams(IndicatorType.SUPERTREND, Map.of()).get("period"));
        assertEquals(3.0, service.resolvedParams(IndicatorType.SUPERTREND, Map.of()).get("k"));
        assertEquals(14, service.resolvedParams(IndicatorType.ADR, Map.of()).get("period"));
    }

    // ---------------- ③ 非法参数被拒并回执 ----------------

    @Test
    @DisplayName("③ 新指标 period=0 / 超范围被拒，回执含 [2, 200]")
    void periodOutOfRangeRejected() {
        BizException e1 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.ADX, Map.of("period", 0), barsFromReference()));
        assertTrue(e1.getMessage().contains("[2, 200]"), e1.getMessage());
        assertTrue(e1.getMessage().contains("ADX"), e1.getMessage());

        BizException e2 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.STOCH, Map.of("period", 201), barsFromReference()));
        assertTrue(e2.getMessage().contains("[2, 200]"), e2.getMessage());
    }

    @Test
    @DisplayName("③ Supertrend / Keltner 的 ATR 倍数越界被拒，回执含 [0.5, 5.0]")
    void multiplierOutOfRangeRejected() {
        BizException e1 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.SUPERTREND, Map.of("k", 0.1), barsFromReference()));
        assertTrue(e1.getMessage().contains("[0.5, 5.0]"), e1.getMessage());

        BizException e2 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.KELTNER, Map.of("k", 9), barsFromReference()));
        assertTrue(e2.getMessage().contains("[0.5, 5.0]"), e2.getMessage());
    }

    @Test
    @DisplayName("③ 非整数 period 被拒")
    void nonIntegerPeriodRejected() throws Exception {
        BizException e = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.CCI, Map.of("period", 2.5), barsFromReference()));
        assertTrue(e.getMessage().contains("必须为整数"), e.getMessage());
    }

    // ---------------- 指标组可引用新指标 ----------------

    @Test
    @DisplayName("指标组可引用扩展指标字段（如 ADX 的 adx 字段）")
    void groupCanReferenceExtension() throws Exception {
        List<KlineBar> bars = barsFromReference();
        IndicatorGroupSpec spec = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("adxGt5",
                        IndicatorGroupSpec.Operand.ofIndicator(new IndicatorGroupSpec.Ref(
                                IndicatorType.ADX, Map.of("period", 14), "adx")),
                        IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofConstant(5))
        ), IndicatorGroupSpec.Logic.AND);
        IndicatorGroupSpec.Evaluation e = service.evaluateGroup(bars, spec);
        assertTrue(e.perRule().containsKey("adxGt5"));

        // 字段名写错时回执可选字段
        IndicatorGroupSpec bad = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("bad",
                        IndicatorGroupSpec.Operand.ofIndicator(new IndicatorGroupSpec.Ref(
                                IndicatorType.ADX, Map.of(), "nope")),
                        IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofConstant(5))
        ), IndicatorGroupSpec.Logic.AND);
        BizException ex = assertThrows(BizException.class, () -> service.evaluateGroup(bars, bad));
        assertTrue(ex.getMessage().contains("可选"), ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    // ---------------- 工具 ----------------

    private static void assertClose(String label, JsonNode expected, double[] actual) {
        assertEquals(expected.size(), actual.length, label + " 长度不一致");
        for (int i = 0; i < actual.length; i++) {
            JsonNode e = expected.get(i);
            if (e == null || e.isNull()) {
                assertTrue(Double.isNaN(actual[i]), label + " 索引 " + i + " 应为 NaN，实际 " + actual[i]);
            } else {
                assertEquals(e.asDouble(), actual[i], TOL, label + " 索引 " + i);
            }
        }
    }

    private static JsonNode resource(String name) throws Exception {
        try (InputStream in = IndicatorExtensionTest.class.getResourceAsStream("/indicator/" + name)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试资源：" + name);
            }
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<KlineBar> barsFromReference() throws Exception {
        JsonNode root = resource("reference.json");
        List<KlineBar> bars = new ArrayList<>();
        for (JsonNode n : root.get("klines")) {
            bars.add(new KlineBar(n.get("time").asLong(), n.get("open").asDouble(),
                    n.get("high").asDouble(), n.get("low").asDouble(),
                    n.get("close").asDouble(), n.get("volume").asLong()));
        }
        return bars;
    }

    private static double[] closes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).close();
        }
        return out;
    }

    /** 由收盘价与 low 构造 K 线：high=close+0.5、low=low、open=close。 */
    private static List<KlineBar> bars(double[] closes, double[] lows) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            out.add(new KlineBar(i, closes[i], closes[i] + 0.5, lows[i], closes[i], 1));
        }
        return out;
    }
}