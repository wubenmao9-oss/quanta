package com.benmao.quanta.indicator;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标层单测（M4 验收三条）：
 * <ol>
 *   <li>① 误差在阈值内：与独立参考实现（numpy 生成的 reference.json）逐点比对；</li>
 *   <li>② 可传参：RSI(period=14) 等参数确实生效；</li>
 *   <li>③ 非法参数（period=0 / 非整数 / 超范围）被拒并回执合法边界。</li>
 * </ol>
 */
class IndicatorServiceTest {

    /** 与参考实现的容差（参考值由 numpy 独立实现按 TA-Lib 约定生成，双精度差异远小于此值）。 */
    private static final double TOL = 1e-6;

    private final IndicatorProperties props = new IndicatorProperties();
    private final IndicatorService service = new IndicatorService(new IndicatorParamValidator(props));

    // ---------------- ① 误差在阈值内 ----------------

    @Test
    @DisplayName("① 各指标与独立参考实现逐点误差在阈值内")
    void matchesReferenceImplementation() throws Exception {
        JsonNode root = reference();
        JsonNode exp = root.get("expected");
        List<KlineBar> bars = bars(root.get("klines"));

        assertClose("SMA(5)", exp.get("sma"), service.sma(closes(bars), 5));
        assertClose("EMA(5)", exp.get("ema"), service.ema(closes(bars), 5));
        assertClose("RSI(14)", exp.get("rsi"), service.rsi(closes(bars), 14));
        assertClose("ATR(14)", exp.get("atr"), service.atr(bars, 14));
        assertClose("VOLUME MA(5)", exp.get("volumeMa"), service.volumeMa(bars, 5));

        IndicatorService.MacdResult macd = service.macd(closes(bars), 12, 26, 9);
        assertClose("MACD.dif", exp.get("macdDif"), macd.dif());
        assertClose("MACD.dea", exp.get("macdDea"), macd.dea());
        assertClose("MACD.hist", exp.get("macdHist"), macd.hist());

        IndicatorService.BollResult boll = service.boll(closes(bars), 20, 2.0);
        assertClose("BOLL.mid", exp.get("bollMid"), boll.mid());
        assertClose("BOLL.upper", exp.get("bollUpper"), boll.upper());
        assertClose("BOLL.lower", exp.get("bollLower"), boll.lower());
    }

    @Test
    @DisplayName("① 手工样例：SMA 与 BOLL 可用纸笔核算")
    void handCheckedSamples() {
        double[] v = {1, 2, 3, 4, 5};
        // SMA(3) 末值 = (3+4+5)/3 = 4
        assertArrayEquals(new double[]{Double.NaN, Double.NaN, 2, 3, 4},
                service.sma(v, 3), TOL);
        // BOLL(3, k=1) 在 window[1,2,3]：mean=2, sd=sqrt(2/3)
        IndicatorService.BollResult b = service.boll(v, 3, 1.0);
        double sd = Math.sqrt(2.0 / 3.0);
        assertEquals(2.0, b.mid()[2], TOL);
        assertEquals(2.0 + sd, b.upper()[2], TOL);
        assertEquals(2.0 - sd, b.lower()[2], TOL);
    }

    // ---------------- ② 可传参 ----------------

    @Test
    @DisplayName("② 可传参：RSI(period=14) 生效，且周期不同结果不同")
    void rsiPeriodIsRespected() {
        double[] closes = closes(barsFromReference());

        double[] rsi14 = service.compute(IndicatorType.RSI, Map.of("period", 14), barsFromReference()).get("value");
        double[] rsi7 = service.compute(IndicatorType.RSI, Map.of("period", 7), barsFromReference()).get("value");

        // 首个有效值与周期一致：RSI(14) 在索引 14，RSI(7) 在索引 7
        assertTrue(Double.isNaN(rsi14[13]) && !Double.isNaN(rsi14[14]), "RSI(14) 首个有效值应在索引 14");
        assertTrue(Double.isNaN(rsi7[6]) && !Double.isNaN(rsi7[7]), "RSI(7) 首个有效值应在索引 7");
        assertNotEquals(rsi14[rsi14.length - 1], rsi7[rsi7.length - 1], "不同周期应得到不同结果");
        // 与直接传参调用一致
        assertArrayEquals(rsi14, service.rsi(closes, 14), TOL);
    }

    @Test
    @DisplayName("② MACD/BOLL 参数同样可传且生效")
    void macdBollParamsAreRespected() {
        List<KlineBar> bars = barsFromReference();
        IndicatorService.MacdResult a = service.macd(closes(bars), 12, 26, 9);
        IndicatorService.MacdResult b = service.macd(closes(bars), 5, 10, 4);
        assertTrue(firstValidIndex(a.dif()) > firstValidIndex(b.dif()), "更慢的参数预热更久");
        assertNotEquals(a.dif()[a.dif().length - 1], b.dif()[b.dif().length - 1]);

        IndicatorService.BollResult c = service.boll(closes(bars), 20, 2.0);
        IndicatorService.BollResult d = service.boll(closes(bars), 20, 3.0);
        double widthC = c.upper()[79] - c.lower()[79];
        double widthD = d.upper()[79] - d.lower()[79];
        assertTrue(widthD > widthC, "k 越大带宽越宽");
    }

    // ---------------- ③ 非法参数被拒并回执 ----------------

    @Test
    @DisplayName("③ period=0 被拒，回执含合法范围 [2, 200]")
    void periodZeroRejected() {
        BizException e = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.RSI, Map.of("period", 0), barsFromReference()));
        assertTrue(e.getMessage().contains("[2, 200]"), e.getMessage());
        assertTrue(e.getMessage().contains("RSI"), e.getMessage());
    }

    @Test
    @DisplayName("③ 超范围 period=201 被拒，回执含合法范围")
    void periodTooLargeRejected() {
        BizException e = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.SMA, Map.of("period", 201), barsFromReference()));
        assertTrue(e.getMessage().contains("[2, 200]"), e.getMessage());
    }

    @Test
    @DisplayName("③ 非整数 period（字符串 / 小数）被拒")
    void nonIntegerPeriodRejected() {
        BizException e1 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.RSI, Map.of("period", "abc"), barsFromReference()));
        assertTrue(e1.getMessage().contains("必须为整数"), e1.getMessage());

        BizException e2 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.RSI, Map.of("period", 2.5), barsFromReference()));
        assertTrue(e2.getMessage().contains("必须为整数"), e2.getMessage());
    }

    @Test
    @DisplayName("③ BOLL 的 k 超范围被拒且回执边界；MACD fast≥slow 被拒")
    void bollAndMacdBoundsRejected() {
        BizException e1 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.BOLL, Map.of("k", 9), barsFromReference()));
        assertTrue(e1.getMessage().contains("[0.5, 5.0]"), e1.getMessage());

        BizException e2 = assertThrows(BizException.class, () ->
                service.compute(IndicatorType.MACD, Map.of("fast", 30, "slow", 10), barsFromReference()));
        assertTrue(e2.getMessage().contains("必须小于"), e2.getMessage());
    }

    @Test
    @DisplayName("③ 未知指标名被拒并回执可选值")
    void unknownIndicatorRejected() {
        BizException e = assertThrows(BizException.class, () -> IndicatorType.parse("XYZ"));
        assertTrue(e.getMessage().contains("可选"), e.getMessage());
        assertTrue(e.getMessage().contains("RSI"), e.getMessage());
    }

    @Test
    @DisplayName("③ 参数合法时返回定型参数表（含默认值）")
    void resolvedParamsEchoDefaults() {
        Map<String, Object> resolved = service.resolvedParams(IndicatorType.MACD, Map.of("fast", 10));
        assertEquals(10, resolved.get("fast"));
        assertEquals(26, resolved.get("slow"));
        assertEquals(9, resolved.get("signal"));
    }

    // ---------------- 指标组 ----------------

    @Test
    @DisplayName("指标组：AND/OR 联合命中 + 逐条回执")
    void indicatorGroupLogic() {
        List<KlineBar> bars = bars(new double[]{10, 11, 12, 13, 14, 15}, new long[]{100, 100, 100, 100, 100, 100});

        IndicatorGroupSpec and = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("smaGt15", smaRef(2), IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofConstant(15)),
                new IndicatorGroupSpec.Rule("priceGt14", IndicatorGroupSpec.Operand.ofPrice(IndicatorGroupSpec.PriceField.CLOSE),
                        IndicatorGroupSpec.Op.GT, IndicatorGroupSpec.Operand.ofConstant(14))
        ), IndicatorGroupSpec.Logic.AND);
        IndicatorGroupSpec.Evaluation e1 = service.evaluateGroup(bars, and);
        assertFalse(e1.matched(), "AND：有一条不满足则整体不命中");
        assertEquals(Boolean.FALSE, e1.perRule().get("smaGt15"), "SMA(2)=14.5 不大于 15");
        assertEquals(Boolean.TRUE, e1.perRule().get("priceGt14"), "close=15 大于 14");

        IndicatorGroupSpec or = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("smaLt14", smaRef(2), IndicatorGroupSpec.Op.LT,
                        IndicatorGroupSpec.Operand.ofConstant(14)),
                new IndicatorGroupSpec.Rule("priceGt14", IndicatorGroupSpec.Operand.ofPrice(IndicatorGroupSpec.PriceField.CLOSE),
                        IndicatorGroupSpec.Op.GT, IndicatorGroupSpec.Operand.ofConstant(14))
        ), IndicatorGroupSpec.Logic.OR);
        IndicatorGroupSpec.Evaluation e2 = service.evaluateGroup(bars, or);
        assertTrue(e2.matched(), "OR：只要有一条满足即命中");
        assertEquals(Boolean.FALSE, e2.perRule().get("smaLt14"));
        assertEquals(Boolean.TRUE, e2.perRule().get("priceGt14"));
    }

    @Test
    @DisplayName("指标组：指标 vs 指标、CROSS_UP、RSI/BOLL 典型组合")
    void indicatorGroupMoreForms() {
        List<KlineBar> bars = bars(new double[]{10, 11, 12, 13, 14, 15}, new long[]{100, 100, 100, 100, 100, 100});

        IndicatorGroupSpec indVsInd = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("sma2GtSma3", smaRef(2), IndicatorGroupSpec.Op.GT, smaRef(3))
        ), IndicatorGroupSpec.Logic.AND);
        assertTrue(service.evaluateGroup(bars, indVsInd).matched(), "SMA(2)=14.5 > SMA(3)=14");

        IndicatorGroupSpec cross = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("cross11", IndicatorGroupSpec.Operand.ofPrice(IndicatorGroupSpec.PriceField.CLOSE),
                        IndicatorGroupSpec.Op.CROSS_UP, IndicatorGroupSpec.Operand.ofConstant(11.5))
        ), IndicatorGroupSpec.Logic.AND);
        assertTrue(service.evaluateGroupAt(bars, cross, 2).matched(), "索引2：11→12 上穿 11.5");

        // 布林下轨：价格跌破下轨（close < lower）
        IndicatorGroupSpec bollBreak = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("belowLower",
                        IndicatorGroupSpec.Operand.ofIndicator(new IndicatorGroupSpec.Ref(
                                IndicatorType.BOLL, Map.of("period", 2, "k", 1.0), "lower")),
                        IndicatorGroupSpec.Op.GT,
                        IndicatorGroupSpec.Operand.ofPrice(IndicatorGroupSpec.PriceField.CLOSE))
        ), IndicatorGroupSpec.Logic.AND);
        assertFalse(service.evaluateGroup(bars, bollBreak).matched(), "价格在上行，不应跌破下轨");
    }

    @Test
    @DisplayName("指标组：空规则 / 未知输出字段 被拒")
    void indicatorGroupInvalidRejected() {
        List<KlineBar> bars = bars(new double[]{10, 11, 12, 13, 14, 15}, new long[]{1, 1, 1, 1, 1, 1});
        BizException e1 = assertThrows(BizException.class, () ->
                service.evaluateGroup(bars, new IndicatorGroupSpec(List.of(), IndicatorGroupSpec.Logic.AND)));
        assertTrue(e1.getMessage().contains("至少需要一条规则"), e1.getMessage());

        IndicatorGroupSpec bad = new IndicatorGroupSpec(List.of(
                new IndicatorGroupSpec.Rule("bad", IndicatorGroupSpec.Operand.ofIndicator(
                        new IndicatorGroupSpec.Ref(IndicatorType.BOLL, Map.of(), "foo")),
                        IndicatorGroupSpec.Op.GT, IndicatorGroupSpec.Operand.ofConstant(1))
        ), IndicatorGroupSpec.Logic.AND);
        BizException e2 = assertThrows(BizException.class, () -> service.evaluateGroup(bars, bad));
        assertTrue(e2.getMessage().contains("不支持输出字段"), e2.getMessage());
    }

    // ---------------- 工具 ----------------

    private static IndicatorGroupSpec.Operand smaRef(int period) {
        return IndicatorGroupSpec.Operand.ofIndicator(
                new IndicatorGroupSpec.Ref(IndicatorType.SMA, Map.of("period", period), "value"));
    }

    private static List<KlineBar> barsFromReference() {
        try {
            return bars(reference().get("klines"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode reference() throws Exception {
        try (InputStream in = IndicatorServiceTest.class.getResourceAsStream("/indicator/reference.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少测试资源 /indicator/reference.json");
            }
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<KlineBar> bars(JsonNode klines) throws Exception {
        return new ObjectMapper().convertValue(klines, new TypeReference<List<KlineBar>>() {});
    }

    private static List<KlineBar> bars(double[] closes, long[] volumes) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(new KlineBar(1760000000L + i * 60L, c, c + 0.5, c - 0.5, c, volumes[i]));
        }
        return out;
    }

    private static double[] closes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bars.get(i).close();
        }
        return out;
    }

    private static int firstValidIndex(double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) {
                return i;
            }
        }
        return -1;
    }

    private static void assertClose(String name, JsonNode expected, double[] actual) {
        double[] want = new double[expected.size()];
        for (int i = 0; i < want.length; i++) {
            want[i] = expected.get(i).isNull() ? Double.NaN : expected.get(i).asDouble();
        }
        assertEquals(want.length, actual.length, name + " 长度不一致");
        for (int i = 0; i < want.length; i++) {
            if (Double.isNaN(want[i])) {
                assertTrue(Double.isNaN(actual[i]), name + " 索引" + i + " 应为预热 NaN");
            } else {
                assertEquals(want[i], actual[i], TOL, name + " 索引" + i + " 误差超阈值");
            }
        }
    }
}