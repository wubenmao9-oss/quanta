package com.benmao.quanta.agent;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.indicator.IndicatorService;
import com.benmao.quanta.indicator.IndicatorType;
import com.benmao.quanta.mapper.StrategyMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的<b>只读工具</b>（设计 §3：「只读工具自由用」）。
 *
 * <p>这些方法经 {@code @Tool} 暴露给大模型直接调用；<b>全部只读</b>，不产生任何副作用，
 * 故无需经过风控。任何异常都被收敛为可读的 {@code {"error": ...}} 结果回传给模型，让其自我修正，绝不中断决策链路。
 *
 * <p>写操作（OPEN/CLOSE/MODIFY/PLAN）<b>不</b>在此暴露，见 {@link AgentWriteTools}。
 */
@Component("agentMarketTools")
public class AgentMarketTools {

    private final Mt5BridgeService bridge;
    private final IndicatorService indicatorService;
    private final StrategyMapper strategyMapper;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public AgentMarketTools(Mt5BridgeService bridge,
                            IndicatorService indicatorService,
                            StrategyMapper strategyMapper,
                            AgentProperties props,
                            ObjectMapper mapper) {
        this.bridge = bridge;
        this.indicatorService = indicatorService;
        this.strategyMapper = strategyMapper;
        this.props = props;
        this.mapper = mapper;
    }

    @Tool("获取账户资金：结余 balance、净值 equity、可用保证金 freeMargin、浮动盈亏 profit、杠杆 leverage、币种 currency")
    public Map<String, Object> accountInfo() {
        try {
            AccountInfo a = bridge.account();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("login", a.login());
            m.put("currency", a.currency());
            m.put("balance", a.balance());
            m.put("equity", a.equity());
            m.put("margin", a.margin());
            m.put("freeMargin", a.freeMargin());
            m.put("profit", a.profit());
            m.put("leverage", a.leverage());
            return m;
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool("获取品种实时报价：bid/ask/spread（点）/digits；symbol 可用逻辑名 XAUUSD 或券商名 GOLD")
    public Map<String, Object> quote(@P("品种，如 GOLD") String symbol) {
        try {
            Quote q = bridge.quote(resolve(symbol));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", q.symbol());
            m.put("bid", q.bid());
            m.put("ask", q.ask());
            m.put("spreadPoints", q.spread());
            m.put("digits", q.digits());
            m.put("time", q.time());
            return m;
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool("获取当前持仓列表（ticket、方向、手数、开仓价、现价、SL、TP、浮动盈亏、swap）；symbol 可空=全部")
    public List<Map<String, Object>> positions(@P("品种，可空=全部") String symbol) {
        try {
            List<Position> list = bridge.positions(isBlank(symbol) ? null : resolve(symbol));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Position p : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ticket", p.ticket());
                m.put("symbol", p.symbol());
                m.put("type", p.type());
                m.put("volume", p.volume());
                m.put("openPrice", p.openPrice());
                m.put("currentPrice", p.currentPrice());
                m.put("sl", p.sl());
                m.put("tp", p.tp());
                m.put("profit", p.profit());
                m.put("swap", p.swap());
                m.put("openTime", p.openTime());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    @Tool("获取最近 K 线（OHLCV）；timeframe 取 M1/M5/M15/M30/H1/H4/D1，count 建议 20~300")
    public List<Map<String, Object>> klines(@P("品种，如 GOLD") String symbol,
                                            @P("周期，如 M5") String timeframe,
                                            @P("根数，可空=默认") Integer count) {
        try {
            int n = count == null ? props.getKlineCount() : count;
            n = Math.max(1, Math.min(500, n));
            List<KlineBar> bars = bridge.klines(resolve(symbol), isBlank(timeframe) ? props.getDefaultTimeframe() : timeframe, n);
            List<Map<String, Object>> out = new ArrayList<>(bars.size());
            for (KlineBar b : bars) {
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
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    @Tool("计算并返回指标最新值；type 取 EMA/SMA/RSI/MACD/BOLL/ATR/ADX 等，paramsJson 如 {\"period\":14}（可空=默认参数）")
    public Map<String, Object> indicators(@P("品种，如 GOLD") String symbol,
                                          @P("周期，如 M5") String timeframe,
                                          @P("指标名，如 RSI") String type,
                                          @P("参数 JSON，可空") String paramsJson) {
        try {
            IndicatorType it = IndicatorType.parse(type);
            Map<String, Object> params = parseParams(paramsJson);
            List<KlineBar> bars = bridge.klines(resolve(symbol),
                    isBlank(timeframe) ? props.getDefaultTimeframe() : timeframe, props.getKlineCount());
            Map<String, double[]> series = indicatorService.compute(it, params, bars);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("indicator", it.name());
            out.put("params", indicatorService.resolvedParams(it, params));
            Map<String, Object> latest = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : series.entrySet()) {
                latest.put(e.getKey(), lastValid(e.getValue()));
            }
            out.put("latest", latest);
            return out;
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool("获取当前 ACTIVE 状态的可交易策略清单（id/名称/regime/目标盈亏比）")
    public List<Map<String, Object>> activeStrategies() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Strategy s : strategyMapper.listAll()) {
                if (!"ACTIVE".equalsIgnoreCase(s.getStatus())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("regimeType", s.getRegimeType());
                m.put("targetRr", s.getTargetRr());
                m.put("version", s.getVersion());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    // ==================== 内部实现 ====================

    private String resolve(String symbol) {
        return isBlank(symbol) ? props.getDefaultSymbol() : symbol;
    }

    private Map<String, Object> parseParams(String paramsJson) {
        if (isBlank(paramsJson)) {
            return Map.of();
        }
        try {
            return mapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("paramsJson 不是合法 JSON 对象：" + paramsJson);
        }
    }

    /** 取最后一个非 NaN 值；全为 NaN 时返回 null（预热期）。 */
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

    private static Map<String, Object> err(RuntimeException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
