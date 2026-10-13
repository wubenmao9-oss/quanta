package com.benmao.quanta.memory;

import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.AgentMemory;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.AgentMemoryMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆层（M11，设计 §11）：把「会话内若干次决策」压缩成<b>结构化记忆卡</b>（表 {@code agent_memory}）。
 *
 * <p><b>触发</b>：会话内每累计 {@code summaryEvery}（默认 10，可配 5~50）次决策产出一张卡——
 * 计数以 {@code agent_decision_log} 的会话内条数为准（每个会话窗口是独立对话记忆，设计 §6.3）。
 *
 * <p><b>结构化、非自由文本</b>（设计 §6 能力表）：卡片摘要由动作分布 / 平均置信度 / 主导动作 /
 * 最近理由等字段化信息拼装，<b>不调用大模型</b>，保证可检索、可复现、零 token。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 记忆卡作用域：绑定策略维度。 */
    public static final String SCOPE_STRATEGY = "STRATEGY";
    /** 记忆卡作用域：仅绑定品种维度。 */
    public static final String SCOPE_SYMBOL = "SYMBOL";

    private final MemoryProperties props;
    private final AgentDecisionLogMapper logMapper;
    private final AgentMemoryMapper memoryMapper;
    private final StrategyMapper strategyMapper;

    public MemoryService(MemoryProperties props, AgentDecisionLogMapper logMapper, AgentMemoryMapper memoryMapper,
                         StrategyMapper strategyMapper) {
        this.props = props;
        this.logMapper = logMapper;
        this.memoryMapper = memoryMapper;
        this.strategyMapper = strategyMapper;
    }

    /**
     * 一次决策落库后的记忆钩子（best-effort，由 {@code AgentService} 调用）。
     *
     * <p>仅当该会话累计决策数达到 {@code summaryEvery} 的整数倍时产出记忆卡；否则直接返回 {@code null}。
     *
     * @param sessionId  会话 id（必填；为空则跳过）
     * @param symbol     品种（可空）
     * @param strategyId 策略 id（可空；有则作用域为 STRATEGY）
     * @param regime     行情状态（可空，一期留空）
     * @return 新产出的记忆卡；未达阈值时返回 {@code null}
     */
    public AgentMemory afterDecision(Long sessionId, String symbol, Long strategyId, String regime) {
        if (!props.isEnabled() || sessionId == null) {
            return null;
        }
        int every = props.requireSummaryEvery();
        int total = logMapper.countBySession(sessionId);
        if (total <= 0 || total % every != 0) {
            return null;
        }
        return summarize(sessionId, symbol, strategyId, regime, total, every);
    }

    /**
     * 强制对某会话的最近 {@code summaryEvery} 次决策产出记忆卡（供手工/测试触发）。
     *
     * @return 新产出的记忆卡
     */
    public AgentMemory summarize(Long sessionId, String symbol, Long strategyId, String regime) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        int every = props.requireSummaryEvery();
        int total = logMapper.countBySession(sessionId);
        return summarize(sessionId, symbol, strategyId, regime, total, every);
    }

    private AgentMemory summarize(Long sessionId, String symbol, Long strategyId, String regime,
                                  int total, int every) {
        List<AgentDecisionLog> window = logMapper.listRecentBySession(sessionId, every);
        Map<String, Integer> actionCounts = countActions(window);
        double avgConfidence = averageConfidence(window);
        String dominant = dominantAction(actionCounts);
        String latestReason = latestReason(window);

        String summary = renderSummary(total, actionCounts, avgConfidence, dominant, latestReason);
        String tags = renderTags(sessionId, symbol, strategyId, total, actionCounts);

        AgentMemory card = new AgentMemory();
        card.setScope(strategyId != null ? SCOPE_STRATEGY : SCOPE_SYMBOL);
        card.setSymbol(blankToNull(symbol));
        card.setStrategyId(strategyId);
        card.setRegime(resolveRegime(regime, strategyId));
        card.setSummary(summary);
        card.setTags(tags);
        card.setSourceTrade(null);
        memoryMapper.insert(card);

        log.info("产出记忆卡 id={} session={} scope={} decisions={} 主导动作={} 平均置信度={}",
                card.getId(), sessionId, card.getScope(), total, dominant, String.format(Locale.ROOT, "%.2f", avgConfidence));
        return card;
    }

    // ==================== 检索（设计 §11 一期：按 scope/symbol/strategy/tags 检索） ====================

    /** 记忆卡检索；过滤优先级：tag > strategyId > scope > symbol，均空则返回最近卡片。 */
    public List<AgentMemory> search(String scope, String symbol, Long strategyId, String tag, int limit) {
        int n = limit > 0 ? Math.min(limit, 200) : props.getListDefaultLimit();
        if (tag != null && !tag.isBlank()) {
            return memoryMapper.listByTag(tag.trim(), n);
        }
        if (strategyId != null) {
            return memoryMapper.listByStrategy(strategyId, n);
        }
        if (scope != null && !scope.isBlank()) {
            return memoryMapper.listByScope(scope.trim().toUpperCase(Locale.ROOT), n);
        }
        if (symbol != null && !symbol.isBlank()) {
            return memoryMapper.listBySymbol(symbol.trim(), n);
        }
        return memoryMapper.listRecent(n);
    }

    // ==================== 内部实现 ====================

    private static Map<String, Integer> countActions(List<AgentDecisionLog> window) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentDecisionLog d : window) {
            String a = d.getAction() == null ? "UNKNOWN" : d.getAction().toUpperCase(Locale.ROOT);
            counts.merge(a, 1, Integer::sum);
        }
        return counts;
    }

    private static double averageConfidence(List<AgentDecisionLog> window) {
        double sum = 0.0;
        int n = 0;
        for (AgentDecisionLog d : window) {
            BigDecimal c = d.getConfidence();
            if (c != null) {
                sum += c.doubleValue();
                n++;
            }
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static String dominantAction(Map<String, Integer> counts) {
        String best = "-";
        int max = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static String latestReason(List<AgentDecisionLog> window) {
        // listRecentBySession 已按 id DESC，第一条即最近一次
        for (AgentDecisionLog d : window) {
            if (d.getReason() != null && !d.getReason().isBlank()) {
                return d.getReason().trim();
            }
        }
        return "";
    }

    private String renderSummary(int total, Map<String, Integer> counts, double avgConfidence,
                                 String dominant, String latestReason) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getKey() + e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("累计决策 ").append(total).append(" 次；")
                .append("动作分布 ").append(String.join("/", parts)).append("；")
                .append("主导动作 ").append(dominant).append("；")
                .append("平均置信度 ").append(String.format(Locale.ROOT, "%.2f", avgConfidence));
        if (!latestReason.isEmpty()) {
            sb.append("；最近理由：").append(latestReason);
        }
        return truncate(sb.toString(), props.getSummaryMaxLen());
    }

    private static String renderTags(Long sessionId, String symbol, Long strategyId, int total,
                                     Map<String, Integer> counts) {
        List<String> tags = new ArrayList<>();
        tags.add("session:" + sessionId);
        if (symbol != null && !symbol.isBlank()) {
            tags.add("symbol:" + symbol.trim());
        }
        tags.add("strategy:" + (strategyId == null ? "-" : strategyId));
        tags.add("decisions:" + total);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            tags.add(e.getKey().toLowerCase(Locale.ROOT) + ":" + e.getValue());
        }
        return String.join(",", tags);
    }

    private static String truncate(String s, int max) {
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 行情状态：优先取显式入参；为空且有策略时，回落到策略自身的 {@code regime_type}（TREND/RANGE）。 */
    private String resolveRegime(String regime, Long strategyId) {
        if (regime != null && !regime.isBlank()) {
            return regime.trim().toUpperCase(Locale.ROOT);
        }
        if (strategyId == null) {
            return null;
        }
        Strategy s = strategyMapper.getById(strategyId);
        if (s == null || s.getRegimeType() == null || s.getRegimeType().isBlank()) {
            return null;
        }
        return s.getRegimeType().trim().toUpperCase(Locale.ROOT);
    }
}
