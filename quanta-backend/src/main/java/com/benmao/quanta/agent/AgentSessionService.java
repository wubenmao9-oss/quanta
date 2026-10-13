package com.benmao.quanta.agent;

import com.benmao.quanta.entity.AgentSession;
import com.benmao.quanta.mapper.AgentSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Agent 会话落地（表 {@code agent_session}）的最小实现。
 *
 * <p>M10 只为 {@code agent_decision_log.session_id}（NOT NULL）提供承载：一次决策若未带会话，
 * 则新建一条 ACTIVE 会话并返回其 id；若带会话且存在则复用并累加消息数。
 *
 * <p>完整会话生命周期（等待/关闭/记忆总结）由 M11 记忆/复盘模块接管。
 */
@Service
public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    private final AgentSessionMapper mapper;

    public AgentSessionService(AgentSessionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取或建会话。
     *
     * @param sessionId   期望复用的会话 id（可空）
     * @param symbol      品种
     * @param strategyId  策略 id（可空）
     * @param llmConfigId 大模型配置 id（可空）
     * @param sessionType AUTO / MANUAL
     * @return 会话 id（保证存在）
     */
    public Long ensureSession(Long sessionId, String symbol, Long strategyId, Long llmConfigId, String sessionType) {
        if (sessionId != null) {
            AgentSession existing = mapper.getById(sessionId);
            if (existing != null) {
                String type = existing.getSessionType();
                AgentSession touch = new AgentSession();
                touch.setId(existing.getId());
                touch.setSessionNo(existing.getSessionNo());
                touch.setSessionType(type == null ? "AUTO" : type);
                touch.setSymbol(existing.getSymbol());
                touch.setStrategyId(existing.getStrategyId());
                touch.setLlmConfigId(existing.getLlmConfigId());
                touch.setStatus(existing.getStatus());
                touch.setLinkedTicket(existing.getLinkedTicket());
                touch.setMessageCount((existing.getMessageCount() == null ? 0 : existing.getMessageCount()) + 1);
                touch.setCloseTime(existing.getCloseTime());
                mapper.update(touch);
                return existing.getId();
            }
            log.warn("指定的会话 {} 不存在，改为新建会话", sessionId);
        }
        AgentSession s = new AgentSession();
        s.setSessionNo(nextSessionNo());
        s.setSessionType(normalizeType(sessionType));
        s.setSymbol(symbol);
        s.setStrategyId(strategyId);
        s.setLlmConfigId(llmConfigId);
        s.setStatus("ACTIVE");
        s.setMessageCount(1);
        mapper.insert(s);
        log.info("已新建 Agent 会话: id={}, no={}, symbol={}, strategyId={}",
                s.getId(), s.getSessionNo(), symbol, strategyId);
        return s.getId();
    }

    /** 关闭会话（M11 会用；此处提供最小能力）。 */
    public void close(Long sessionId) {
        AgentSession s = mapper.getById(sessionId);
        if (s == null) {
            return;
        }
        s.setStatus("CLOSED");
        s.setCloseTime(LocalDateTime.now());
        mapper.update(s);
    }

    /** 生成下一个会话序号：现有最大 session_no + 1。 */
    private int nextSessionNo() {
        List<AgentSession> all = mapper.listAll();
        int max = 0;
        for (AgentSession s : all) {
            if (s.getSessionNo() != null && s.getSessionNo() > max) {
                max = s.getSessionNo();
            }
        }
        return max + 1;
    }

    private static String normalizeType(String t) {
        if (t == null || t.isBlank()) {
            return "AUTO";
        }
        String v = t.trim().toUpperCase(Locale.ROOT);
        return ("MANUAL".equals(v) || "AUTO".equals(v)) ? v : "AUTO";
    }
}
