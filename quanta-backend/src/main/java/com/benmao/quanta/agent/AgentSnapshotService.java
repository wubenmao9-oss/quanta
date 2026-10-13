package com.benmao.quanta.agent;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.indicator.IndicatorType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 构建实时「行情快照」，供 ①写入 {@code agent_decision_log.input_snapshot}（可回放）、
 * ②作为用户消息的一部分喂给大模型。
 *
 * <p>工程考虑：任一子模块（行情/账户/持仓/指标）取数失败都<b>不</b>中断整体——该分区记为
 * {@code {"error": ...}}，其余照常，确保 Agent 决策链路始终可推进（失败即保守由风控兜底）。
 */
@Component
public class AgentSnapshotService {

    private final Mt5BridgeService bridge;
    private final IndicatorService indicatorService;
    private final AgentProperties props;

    public AgentSnapshotService(Mt5BridgeService bridge, IndicatorService indicatorService, AgentProperties props) {
        this.bridge = bridge;
        this.indicatorService = indicatorService;
        this.props = props;
    }

    /** 组装快照（symbol 为券商真实名或逻辑名）。 */
    public Map<String, Object> build(String symbol, String timeframe) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("symbol", symbol);
        snap.put("timeframe", timeframe);
        snap.put("asOf", LocalDateTime.now().toString());

        put(snap, "quote", () -> quote(symbol));
        put(snap, "account", this::account);
        put(snap, "positions", () -> positions(symbol));

        List<KlineBar> bars = safe(() -> bridge.klines(symbol, timeframe, props.getKlineCount()));
        if (bars == null || bars.isEmpty()) {
            snap.put("recentBars", Map.of("error", "无 K 线数据"));
            snap.put("indicators", Map.of("error", "无 K 线数据，无法计算指标"));
        } else {
            snap.put("recentBars", recentBars(bars));
            snap.put("indicators", indicators(bars));
        }
        return snap;
    }

    private Map<String, Object> quote(String symbol) {
        Quote q = bridge.quote(symbol);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bid", q.bid());
        m.put("ask", q.ask());
        m.put("spreadPoints", q.spread());
        m.put("digits", q.digits());
        return m;
    }

    private Map<String, Object> account() {
        AccountInfo a = bridge.account();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", a.currency());
        m.put("balance", a.balance());
        m.put("equity", a.equity());
        m.put("freeMargin", a.freeMargin());
        m.put("profit", a.profit());
        m.put("leverage", a.leverage());
        return m;
    }

    private List<Map<String, Object>> positions(String symbol) {
        List<Position> list = bridge.positions(symbol);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Position p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket", p.ticket());
            m.put("type", p.type());
            m.put("volume", p.volume());
            m.put("openPrice", p.openPrice());
            m.put("currentPrice", p.currentPrice());
            m.put("sl", p.sl());
            m.put("tp", p.tp());
            m.put("profit", p.profit());
            m.put("swap", p.swap());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> recentBars(List<KlineBar> bars) {
        int n = Math.max(1, props.getSnapshotBarCount());
        int from = Math.max(0, bars.size() - n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = from; i < bars.size(); i++) {
            KlineBar b = bars.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", b.time());
            m.put("open", b.open());
            m.put("high", b.high());
            m.put("low", b.low());
            m.put("close", b.close());
            m.put("volume", b.volume());
            out.add(m);
        }
        return out;
    }

    /** 关键指标的最新值（默认参数）。 */
    private Map<String, Object> indicators(List<KlineBar> bars) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "ema20", () -> latest(bars, IndicatorType.EMA, Map.of("period", 20)));
        put(out, "ema50", () -> latest(bars, IndicatorType.EMA, Map.of("period", 50)));
        put(out, "rsi14", () -> latest(bars, IndicatorType.RSI, Map.of("period", 14)));
        put(out, "atr14", () -> latest(bars, IndicatorType.ATR, Map.of("period", 14)));
        put(out, "boll20", () -> boll(bars));
        put(out, "adx14", () -> latest(bars, IndicatorType.ADX, Map.of("period", 14)));
        return out;
    }

    private Map<String, Object> latest(List<KlineBar> bars, IndicatorType type, Map<String, Object> params) {
        Map<String, double[]> series = indicatorService.compute(type, params, bars);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : series.entrySet()) {
            m.put(e.getKey(), lastValid(e.getValue()));
        }
        return m;
    }

    private Map<String, Object> boll(List<KlineBar> bars) {
        Map<String, double[]> series = indicatorService.compute(IndicatorType.BOLL, Map.of("period", 20, "k", 2.0), bars);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : series.entrySet()) {
            m.put(e.getKey(), lastValid(e.getValue()));
        }
        return m;
    }

    private static Double lastValid(double[] arr) {
        if (arr == null) {
            return null;
        }
        for (int i = arr.length - 1; i >= 0; i--) {
            if (!Double.isNaN(arr[i])) {
                return arr[i];
            }
        }
        return null;
    }

    private static void put(Map<String, Object> target, String key, Supplier<Object> supplier) {
        try {
            target.put(key, supplier.get());
        } catch (RuntimeException e) {
            target.put(key, Map.of("error", msg(e)));
        }
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String msg(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
