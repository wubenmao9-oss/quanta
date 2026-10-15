package com.benmao.quanta.agent;

import com.benmao.quanta.entity.AgentMessage;
import com.benmao.quanta.entity.AgentSession;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.AgentMessageMapper;
import com.benmao.quanta.mapper.AgentSessionMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话生命周期查询（供前端「AI 对话」页展示与清理）。
 *
 * <p><b>会话模型</b>：一个在场持仓对应一个会话——开仓成功的会话被绑定 {@code linked_ticket}
 * 并保活；持仓了结后该会话自动关闭，不再出现在列表中。因此默认列表的条数＝当前在场持仓数。
 *
 * <p>本服务提供只读查询、会话消息还原、会话关闭（复用 {@link AgentSessionService#close(Long)}），
 * 以及「持仓了结即自动关闭会话」的生命周期对账，不触碰任何交易逻辑。
 */
@Service
public class AgentSessionQueryService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionQueryService.class);

    private static final int MAX_LIMIT = 200;

    private final AgentSessionMapper sessionMapper;
    private final StrategyMapper strategyMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentSessionService sessionService;

    public AgentSessionQueryService(AgentSessionMapper sessionMapper,
                                    StrategyMapper strategyMapper,
                                    TradeOrderMapper tradeOrderMapper,
                                    AgentMessageMapper messageMapper,
                                    AgentSessionService sessionService) {
        this.sessionMapper = sessionMapper;
        this.strategyMapper = strategyMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.messageMapper = messageMapper;
        this.sessionService = sessionService;
    }

    /**
     * 列出会话（最新在前）。查询前先做一次生命周期对账：
     * 某会话若下过单、且其单已全部离场（无 status='FILLED' 的在场单），
     * 则该会话视为随持仓生命终结，自动置为 CLOSED。
     *
     * @param status ACTIVE（默认，只列「正在管理在场持仓」的会话）/ CLOSED / all
     * @param limit  条数上限
     */
    public List<Map<String, Object>> list(String status, int limit) {
        int n = limit > 0 ? Math.min(limit, MAX_LIMIT) : 100;
        boolean all = status != null
                && ("all".equalsIgnoreCase(status.trim()) || "*".equals(status.trim()));
        List<AgentSession> all2 = sessionMapper.listAll();
        if (reconcileClosedPositions(all2) > 0) {
            all2 = sessionMapper.listAll();
        }
        Map<Long, TradeOrder> openBySession = openOrdersBySession();

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = all2.size() - 1; i >= 0 && out.size() < n; i--) {
            AgentSession s = all2.get(i);
            if (all) {
                out.add(view(s, openBySession.get(s.getId())));
                continue;
            }
            // 默认：只列「正在管理在场持仓」的会话（ACTIVE 且其订单仍有 FILLED）——一持仓一会话
            if (!"ACTIVE".equalsIgnoreCase(String.valueOf(s.getStatus()))) {
                continue;
            }
            TradeOrder open = openBySession.get(s.getId());
            if (open == null) {
                continue;
            }
            out.add(view(s, open));
        }
        return out;
    }

    /**
     * 会话生命周期对账：把「下过单且已无在场单」的 ACTIVE 会话关闭。
     * 纯研判（未下过单）的会话不自动关闭，避免误杀。
     *
     * @return 本次自动关闭的会话数
     */
    private int reconcileClosedPositions(List<AgentSession> all) {
        Map<Long, long[]> stat = new HashMap<>();
        for (Map<String, Object> r : tradeOrderMapper.sessionOrderStats()) {
            Object sid = r.get("sessionId");
            if (sid == null) {
                continue;
            }
            long total = num(r.get("total"));
            long open = num(r.get("openCnt"));
            stat.put(((Number) sid).longValue(), new long[]{total, open});
        }
        int closed = 0;
        for (AgentSession s : all) {
            if (!"ACTIVE".equalsIgnoreCase(String.valueOf(s.getStatus()))) {
                continue;
            }
            long[] v = stat.get(s.getId());
            if (v == null || v[0] <= 0 || v[1] > 0) {
                continue;
            }
            sessionService.close(s.getId());
            closed++;
        }
        if (closed > 0) {
            log.info("会话生命周期对账：自动关闭 {} 个持仓已了结的会话", closed);
        }
        return closed;
    }

    /** 会话内既有对话消息（user/assistant，按时间升序），供前端还原聊天记录。 */
    public List<Map<String, Object>> messages(Long sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentMessage m : messageMapper.listDialogueAsc(sessionId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("role", m.getRole());
            r.put("content", m.getContent());
            r.put("tokens", m.getTokens());
            r.put("createTime", m.getCreateTime());
            out.add(r);
        }
        return out;
    }

    private Map<Long, TradeOrder> openOrdersBySession() {
        Map<Long, TradeOrder> m = new HashMap<>();
        for (TradeOrder o : tradeOrderMapper.listOpenWithSession()) {
            if (o.getSessionId() != null) {
                m.put(o.getSessionId(), o);
            }
        }
        return m;
    }

    private static long num(Object o) {
        if (o == null) {
            return 0L;
        }
        return o instanceof Number ? ((Number) o).longValue() : Long.parseLong(String.valueOf(o));
    }

    /** 关闭会话（幂等：已是 CLOSED 则原样返回）。 */
    public Map<String, Object> close(Long sessionId) {
        AgentSession s = sessionMapper.getById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在：id=" + sessionId);
        }
        if (!"CLOSED".equalsIgnoreCase(s.getStatus())) {
            sessionService.close(sessionId);
            s = sessionMapper.getById(sessionId);
        }
        return view(s, null);
    }

    private Map<String, Object> view(AgentSession s, TradeOrder open) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("sessionNo", s.getSessionNo());
        m.put("sessionType", s.getSessionType());
        m.put("symbol", s.getSymbol() != null ? s.getSymbol() : (open == null ? null : open.getSymbol()));
        m.put("strategyId", s.getStrategyId());
        m.put("strategyName", strategyName(s.getStrategyId()));
        m.put("status", s.getStatus());
        Long ticket = s.getLinkedTicket() != null ? s.getLinkedTicket() : (open == null ? null : open.getTicket());
        m.put("linkedTicket", ticket);
        m.put("messageCount", s.getMessageCount());
        m.put("createTime", s.getCreateTime());
        m.put("closeTime", s.getCloseTime());
        m.put("managingPosition", open != null);
        if (open != null) {
            m.put("openCmd", open.getCmd());
            m.put("openVolume", open.getVolume());
            m.put("openPrice", open.getPrice());
            m.put("openSl", open.getSl());
            m.put("openTp", open.getTp());
            m.put("openTime", open.getOpenTime());
        }
        return m;
    }

    private String strategyName(Long strategyId) {
        if (strategyId == null) {
            return null;
        }
        Strategy s = strategyMapper.getById(strategyId);
        return s == null ? null : s.getName();
    }
}
