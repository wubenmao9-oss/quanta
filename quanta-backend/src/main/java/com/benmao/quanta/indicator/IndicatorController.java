package com.benmao.quanta.indicator;

import com.benmao.mt5.model.KlineBar;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标层接口：供人工验收与后续 Agent 工具（M10）复用。
 *
 * <p>K 线来源二选一：请求体直接带 {@code klines}；或给 {@code symbol/timeframe/count} 由桥接层实时取数。
 * 非法参数（period=0 / 非整数 / 超范围）由 {@link IndicatorParamValidator} 拒绝并回执合法边界。
 */
@RestController
@RequestMapping("/api/indicator")
public class IndicatorController {

    private final IndicatorService indicator;
    private final Mt5BridgeService bridge;

    public IndicatorController(IndicatorService indicator, Mt5BridgeService bridge) {
        this.indicator = indicator;
        this.bridge = bridge;
    }

    /** 计算单个指标，回显定型参数 + 序列（预热期的 NaN 转 null）。 */
    @PostMapping("/compute")
    public Result<Map<String, Object>> compute(@RequestBody ComputeRequest req) {
        IndicatorType type = IndicatorType.parse(req.indicator());
        Map<String, Object> resolved = indicator.resolvedParams(type, req.params());
        List<KlineBar> bars = resolveKlines(req.symbol(), req.timeframe(), req.count(), req.klines());
        Map<String, double[]> series = indicator.compute(type, resolved, bars);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("indicator", type.name());
        data.put("params", resolved);
        data.put("bars", bars.size());
        data.put("series", toLists(series));
        data.put("latest", latest(series));
        return Result.ok(data);
    }

    /** 评估「指标组」，返回整体是否命中 + 逐条命中情况。 */
    @PostMapping("/group")
    public Result<IndicatorGroupSpec.Evaluation> group(@RequestBody GroupRequest req) {
        List<KlineBar> bars = resolveKlines(req.symbol(), req.timeframe(), req.count(), req.klines());
        return Result.ok(indicator.evaluateGroup(bars, req.group()));
    }

    private List<KlineBar> resolveKlines(String symbol, String timeframe, Integer count, List<KlineBar> klines) {
        if (klines != null && !klines.isEmpty()) {
            return klines;
        }
        String sym = (symbol == null || symbol.isBlank()) ? bridge.defaultSymbol() : symbol;
        String tf = (timeframe == null || timeframe.isBlank()) ? "M5" : timeframe;
        int n = (count == null || count <= 0) ? 200 : count;
        return bridge.klines(sym, tf, n);
    }

    private static Map<String, List<Double>> toLists(Map<String, double[]> series) {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        series.forEach((name, arr) -> {
            List<Double> list = new ArrayList<>(arr.length);
            for (double v : arr) {
                list.add(Double.isNaN(v) ? null : v);
            }
            out.put(name, list);
        });
        return out;
    }

    private static Map<String, Double> latest(Map<String, double[]> series) {
        Map<String, Double> out = new LinkedHashMap<>();
        series.forEach((name, arr) -> {
            for (int i = arr.length - 1; i >= 0; i--) {
                if (!Double.isNaN(arr[i])) {
                    out.put(name, arr[i]);
                    return;
                }
            }
            out.put(name, null);
        });
        return out;
    }

    /** 计算请求：indicator 必填；params 可选；klines 与 symbol/timeframe/count 二选一。 */
    public record ComputeRequest(String indicator,
                                 Map<String, Object> params,
                                 String symbol,
                                 String timeframe,
                                 Integer count,
                                 List<KlineBar> klines) {}

    /** 指标组请求。 */
    public record GroupRequest(IndicatorGroupSpec group,
                               String symbol,
                               String timeframe,
                               Integer count,
                               List<KlineBar> klines) {}
}