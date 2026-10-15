package com.benmao.quanta.agent;

import com.benmao.quanta.backtest.BacktestReport;
import com.benmao.quanta.backtest.BacktestService;
import com.benmao.quanta.backtest.TierScanResult;
import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自由问答（{@code /api/agent/chat}）专用的<b>只读</b>工具集。
 *
 * <p>与交易研判用的 {@link AgentMarketTools} 并存：本类只补充「回测 / 策略库 / 历史成交流水 / 决策日志」
 * 四类只读能力，供前端 AI 助手与「AI 策略对话框」调用。所有方法<b>零副作用</b>，异常一律收敛为
 * {@code {"error": ...}} 回传模型自我修正，绝不中断问答链路。
 *
 * <p>写操作（下单/平仓/改单/建策略）<b>不</b>在此暴露——写路径仍必须走人工确认与 {@code RiskGuard}。
 *
 * <p>参数名一律用 ASCII（{@code @P("symbol")} 等），保证生成的 JSON Schema 属性名合法；
 * 中文说明写在 {@code @Tool} 描述里（0.35.0 的 {@code @P} 只支持参数名，无 description 成员）。
 */
@Component("quantaChatTools")
public class QuantaChatTools {

    /** 单次回测返回的最大逐笔条数（控制 token）。 */
    private static final int MAX_TRADES_IN_RESULT = 30;

    private final BacktestService backtestService;
    private final StrategyMapper strategyMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final AgentDecisionLogMapper decisionLogMapper;

    public QuantaChatTools(BacktestService backtestService,
                           StrategyMapper strategyMapper,
                           TradeOrderMapper tradeOrderMapper,
                           AgentDecisionLogMapper decisionLogMapper) {
        this.backtestService = backtestService;
        this.strategyMapper = strategyMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.decisionLogMapper = decisionLogMapper;
    }

    @Tool("列出可回测的内置规则策略清单（key + 中文名）。回测工具只能用这些 key，无参数")
    public List<Map<String, Object>> availableBacktestStrategies() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BacktestService.StrategyView v : backtestService.availableStrategies()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", v.key());
                m.put("name", v.name());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    @Tool("对某内置规则策略跑单档回测，返回胜率/盈亏比/最大回撤/期望/净利等汇总与少量样本明细。"
            + "参数：symbol(品种如 GOLD)、timeframe(周期 M1/M5/M15/M30/H1/H4/D1)、strategyKey(策略 key)、"
            + "from(起始时间如 2026-09-12)、to(结束时间)、spreadPoints(点差可空)、slippagePoints(滑点可空)")
    public Map<String, Object> runBacktest(@P("symbol") String symbol,
                                           @P("timeframe") String timeframe,
                                           @P("strategyKey") String strategyKey,
                                           @P("from") String from,
                                           @P("to") String to,
                                           @P("spreadPoints") Integer spreadPoints,
                                           @P("slippagePoints") Integer slippagePoints) {
        try {
            BacktestReport r = backtestService.run(new BacktestService.RunSpec(
                    symbol, timeframe, from, to, spreadPoints, slippagePoints, strategyKey));
            return summary(r);
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool("对某内置规则策略做多档点差敏感性扫描，返回各档位汇总与稳健性结论（判断策略是否靠低成本吃饭）。"
            + "参数：symbol、timeframe、strategyKey、from、to、spreadsCsv(点差档位逗号分隔如 0,10,20,30,40)")
    public Map<String, Object> scanBacktestTiers(@P("symbol") String symbol,
                                                @P("timeframe") String timeframe,
                                                @P("strategyKey") String strategyKey,
                                                @P("from") String from,
                                                @P("to") String to,
                                                @P("spreadsCsv") String spreadsCsv) {
        try {
            TierScanResult r = backtestService.runTiers(new BacktestService.TierSpec(
                    symbol, timeframe, from, to, strategyKey, parseSpreads(spreadsCsv), null));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", r.symbol());
            out.put("timeframe", r.timeframe());
            out.put("strategyKey", r.strategyKey());
            out.put("barCount", r.barCount());
            out.put("verdict", r.verdict());
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (TierScanResult.TierSummary t : r.tiers()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("runId", t.runId());
                m.put("spreadPoints", t.spreadPoints());
                m.put("slippagePoints", t.slippagePoints());
                m.put("tradeCount", t.tradeCount());
                m.put("winRatePct", t.winRatePct());
                m.put("profitFactor", t.profitFactor());
                m.put("maxDrawdown", t.maxDrawdown());
                m.put("netProfit", t.netProfit());
                m.put("expectancy", t.expectancy());
                tiers.add(m);
            }
            out.put("tiers", tiers);
            out.put("disclaimer", r.disclaimer());
            return out;
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool("列出库内全部策略（id/名称/状态 DRAFT|DRILL|ACTIVE/regime/目标盈亏比/描述/Prompt 摘要），"
            + "用于设计新策略时避免重复。无参数")
    public List<Map<String, Object>> strategyCatalog() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Strategy s : strategyMapper.listAll()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("status", s.getStatus());
                m.put("version", s.getVersion());
                m.put("regimeType", s.getRegimeType());
                m.put("targetRr", s.getTargetRr());
                m.put("description", s.getDescription());
                m.put("promptSnippet", snippet(s.getPrompt(), 400));
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    @Tool("查询最近已平仓成交流水（ticket/方向/手数/开平价/盈亏/时间），用于复盘真实表现。参数 limit(条数建议 10~50)")
    public List<Map<String, Object>> tradeHistory(@P("limit") Integer limit) {
        try {
            int n = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
            List<TradeOrder> rows = tradeOrderMapper.listRecentClosed(n);
            List<Map<String, Object>> out = new ArrayList<>();
            for (TradeOrder o : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ticket", o.getTicket());
                m.put("symbol", o.getSymbol());
                m.put("side", o.getCmd());
                m.put("volume", o.getVolume());
                m.put("openPrice", o.getPrice());
                m.put("closePrice", o.getClosePrice());
                m.put("profit", o.getProfit());
                m.put("swap", o.getSwap());
                m.put("openTime", o.getOpenTime());
                m.put("closeTime", o.getCloseTime());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    @Tool("查询最近 AI 决策日志（动作/置信度/理由/触发源/模型/token/耗时），用于诊断 AI 是否按预期行动。"
            + "参数 limit(条数建议 10~50)")
    public List<Map<String, Object>> recentDecisions(@P("limit") Integer limit) {
        try {
            int n = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
            List<AgentDecisionLog> rows = decisionLogMapper.listRecent(n);
            List<Map<String, Object>> out = new ArrayList<>();
            for (AgentDecisionLog d : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("sessionId", d.getSessionId());
                m.put("triggerType", d.getTriggerType());
                m.put("action", d.getAction());
                m.put("actionParams", d.getActionParams());
                m.put("confidence", d.getConfidence());
                m.put("reason", d.getReason());
                m.put("model", d.getModel());
                m.put("tokens", d.getTokens());
                m.put("latencyMs", d.getLatencyMs());
                m.put("createTime", d.getCreateTime());
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of(err(e));
        }
    }

    // ==================== 内部实现 ====================

    private static Map<String, Object> summary(BacktestReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("strategyKey", r.strategyKey());
        m.put("strategyName", r.strategyName());
        m.put("symbol", r.symbol());
        m.put("timeframe", r.timeframe());
        m.put("startTime", r.startTime());
        m.put("endTime", r.endTime());
        m.put("barCount", r.barCount());
        m.put("spreadPoints", r.spreadPoints());
        m.put("slippagePoints", r.slippagePoints());
        m.put("tradeCount", r.tradeCount());
        m.put("winRatePct", r.winRatePct());
        m.put("profitFactor", r.profitFactor());
        m.put("maxDrawdown", r.maxDrawdown());
        m.put("avgHoldSec", r.avgHoldSec());
        m.put("expectancy", r.expectancy());
        m.put("grossProfit", r.grossProfit());
        m.put("grossLoss", r.grossLoss());
        m.put("netProfit", r.netProfit());
        m.put("finalEquity", r.finalEquity());
        m.put("disclaimer", r.disclaimer());
        List<Map<String, Object>> trades = new ArrayList<>();
        if (r.trades() != null) {
            int n = Math.min(r.trades().size(), MAX_TRADES_IN_RESULT);
            for (int i = 0; i < n; i++) {
                var t = r.trades().get(i);
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("side", t.side());
                tm.put("volume", t.volume());
                tm.put("openPrice", t.openPrice());
                tm.put("closePrice", t.closePrice());
                tm.put("netProfit", t.netProfit());
                tm.put("holdSec", t.holdSec());
                tm.put("openTime", t.openTime());
                tm.put("closeTime", t.closeTime());
                trades.add(tm);
            }
        }
        m.put("sampleTrades", trades);
        m.put("sampleTradesTruncated", r.trades() != null && r.trades().size() > MAX_TRADES_IN_RESULT);
        return m;
    }

    private static List<Integer> parseSpreads(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("点差档位必须是整数：'" + s + "'，示例 0,10,20,30,40");
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String snippet(String text, int max) {
        if (text == null) {
            return null;
        }
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Map<String, Object> err(RuntimeException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return m;
    }
}
