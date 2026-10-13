package com.benmao.quanta.memory;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.AgentDecisionLog;
import com.benmao.quanta.entity.AgentMemory;
import com.benmao.quanta.entity.Strategy;
import com.benmao.quanta.mapper.AgentDecisionLogMapper;
import com.benmao.quanta.mapper.AgentMemoryMapper;
import com.benmao.quanta.mapper.StrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryService 单测（M11 记忆层）：按会话内决策数触发结构化记忆卡 + 标签检索。
 */
class MemoryServiceTest {

    private MemoryProperties props;
    private AgentDecisionLogMapper logMapper;
    private AgentMemoryMapper memoryMapper;
    private StrategyMapper strategyMapper;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        props.setSummaryEvery(5);
        logMapper = mock(AgentDecisionLogMapper.class);
        memoryMapper = mock(AgentMemoryMapper.class);
        strategyMapper = mock(StrategyMapper.class);
        service = new MemoryService(props, logMapper, memoryMapper, strategyMapper);
        when(memoryMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, AgentMemory.class).setId(88L);
            return 1;
        });
    }

    @Test
    @DisplayName("会话内决策数达 summary-every 整数倍时产出记忆卡（结构化、不调大模型）")
    void producesCardWhenMultipleReached() {
        when(logMapper.countBySession(7L)).thenReturn(10);
        when(logMapper.listRecentBySession(eq(7L), eq(5))).thenReturn(List.of(
                log("BUY", "0.80", "均线多头排列"),
                log("BUY", "0.70", "突破前高"),
                log("WAIT", "0.40", "信号不足"),
                log("BUY", "0.90", "回踩确认"),
                log("WAIT", "0.30", "等待回踩")));
        when(strategyMapper.getById(13L)).thenReturn(strategy(13L, "TREND"));

        AgentMemory card = service.afterDecision(7L, "GOLD", 13L, null);

        assertNotNull(card);
        assertEquals(88L, card.getId());
        assertEquals(MemoryService.SCOPE_STRATEGY, card.getScope());
        assertEquals("GOLD", card.getSymbol());
        assertEquals(13L, card.getStrategyId());
        assertEquals("TREND", card.getRegime());
        assertTrue(card.getSummary().contains("累计决策 10 次"));
        assertTrue(card.getSummary().contains("主导动作 BUY"));
        assertTrue(card.getTags().contains("session:7"));
        assertTrue(card.getTags().contains("strategy:13"));
        assertTrue(card.getTags().contains("decisions:10"));
        verify(memoryMapper).insert(any());
    }

    @Test
    @DisplayName("会话内决策数未达整数倍时不产出（返回 null）")
    void skipsWhenNotMultiple() {
        when(logMapper.countBySession(7L)).thenReturn(3);

        assertNull(service.afterDecision(7L, "GOLD", 13L, null));
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("记忆层关闭时跳过；sessionId 为空时跳过")
    void skipsWhenDisabledOrNoSession() {
        props.setEnabled(false);
        assertNull(service.afterDecision(7L, "GOLD", 13L, null));
        props.setEnabled(true);
        assertNull(service.afterDecision(null, "GOLD", 13L, null));
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("regime 显式入参优先于策略回落")
    void regimeParamWins() {
        when(logMapper.countBySession(7L)).thenReturn(5);
        when(logMapper.listRecentBySession(eq(7L), eq(5))).thenReturn(List.of(log("WAIT", "0.2", "观望")));

        AgentMemory card = service.summarize(7L, "GOLD", 13L, "range");

        assertEquals("RANGE", card.getRegime());
        verify(strategyMapper, never()).getById(any());
    }

    @Test
    @DisplayName("检索过滤优先级：tag > strategyId > scope > symbol > 最近")
    void searchPriority() {
        service.search(null, "GOLD", 13L, "trend", 0);
        verify(memoryMapper).listByTag(eq("trend"), anyInt());

        service.search(null, "GOLD", 13L, null, 0);
        verify(memoryMapper).listByStrategy(eq(13L), anyInt());

        service.search("strategy", "GOLD", null, null, 0);
        verify(memoryMapper).listByScope(eq("STRATEGY"), anyInt());

        service.search("  ", "GOLD", null, null, 0);
        verify(memoryMapper).listBySymbol(eq("GOLD"), anyInt());

        service.search(null, null, null, null, 0);
        verify(memoryMapper).listRecent(anyInt());
    }

    @Test
    @DisplayName("summary-every 非法（越界）时拒绝并回执合法范围")
    void invalidSummaryEveryRejected() {
        props.setSummaryEvery(100);
        BizException ex = assertThrows(BizException.class, () -> service.afterDecision(7L, "GOLD", 13L, null));
        assertTrue(ex.getMessage().contains("[5, 50]"));
    }

    @Test
    @DisplayName("摘要超长时按 summary-max-len 截断")
    void summaryTruncated() {
        props.setSummaryEvery(5);
        props.setSummaryMaxLen(20);
        when(logMapper.countBySession(7L)).thenReturn(5);
        AgentDecisionLog d = log("BUY", "0.8", "这是一个非常非常非常非常非常长的理由描述超过上限");
        when(logMapper.listRecentBySession(eq(7L), eq(5))).thenReturn(List.of(d));

        AgentMemory card = service.summarize(7L, "GOLD", null, null);

        assertEquals(20, card.getSummary().length());
    }

    // ==================== 辅助 ====================

    private static AgentDecisionLog log(String action, String confidence, String reason) {
        AgentDecisionLog d = new AgentDecisionLog();
        d.setAction(action);
        d.setConfidence(new BigDecimal(confidence));
        d.setReason(reason);
        d.setCreateTime(LocalDateTime.now());
        return d;
    }

    private static Strategy strategy(Long id, String regime) {
        Strategy s = new Strategy();
        s.setId(id);
        s.setRegimeType(regime);
        s.setStatus("ACTIVE");
        return s;
    }
}
