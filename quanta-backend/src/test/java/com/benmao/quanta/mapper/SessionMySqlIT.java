package com.benmao.quanta.mapper;

import com.benmao.quanta.entity.AgentMemory;
import com.benmao.quanta.entity.AgentMessage;
import com.benmao.quanta.entity.AgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * agent_session / agent_message / agent_memory 真库集成测试。
 *
 * <p>默认不执行；需真库时：
 * {@code $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test -Dtest=SessionMySqlIT "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class SessionMySqlIT {

    @Autowired
    private AgentSessionMapper agentSessionMapper;

    @Autowired
    private AgentMessageMapper agentMessageMapper;

    @Autowired
    private AgentMemoryMapper agentMemoryMapper;

    @Test
    void agentSession_insertUpdateDelete() {
        AgentSession e = new AgentSession();
        e.setSessionNo(7);
        e.setSessionType("AUTO");
        e.setSymbol("GOLD");
        e.setStrategyId(1L);
        e.setLlmConfigId(2L);
        e.setStatus("ACTIVE");
        e.setLinkedTicket(100L);
        e.setMessageCount(5);
        e.setCloseTime(LocalDateTime.of(2024, 1, 2, 3, 4, 5));

        agentSessionMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        AgentSession loaded = agentSessionMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getSessionNo(), loaded.getSessionNo());
        assertEquals(e.getSessionType(), loaded.getSessionType());
        assertEquals(e.getSymbol(), loaded.getSymbol());
        assertEquals(e.getStrategyId(), loaded.getStrategyId());
        assertEquals(e.getLlmConfigId(), loaded.getLlmConfigId());
        assertEquals(e.getStatus(), loaded.getStatus());
        assertEquals(e.getLinkedTicket(), loaded.getLinkedTicket());
        assertEquals(e.getMessageCount(), loaded.getMessageCount());
        assertEquals(e.getCloseTime(), loaded.getCloseTime());

        loaded.setStatus("CLOSED");
        loaded.setMessageCount(9);
        agentSessionMapper.update(loaded);
        AgentSession updated = agentSessionMapper.getById(e.getId());
        assertEquals("CLOSED", updated.getStatus());
        assertEquals(9, updated.getMessageCount());

        agentSessionMapper.deleteById(e.getId());
        assertNull(agentSessionMapper.getById(e.getId()));
    }

    @Test
    void agentMessage_insertUpdateDelete() {
        AgentMessage e = new AgentMessage();
        e.setSessionId(10L);
        e.setRole("assistant");
        e.setContent("it content");
        e.setTokens(42);

        agentMessageMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        AgentMessage loaded = agentMessageMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getSessionId(), loaded.getSessionId());
        assertEquals(e.getRole(), loaded.getRole());
        assertEquals(e.getContent(), loaded.getContent());
        assertEquals(e.getTokens(), loaded.getTokens());

        loaded.setContent("it content updated");
        loaded.setTokens(43);
        agentMessageMapper.update(loaded);
        AgentMessage updated = agentMessageMapper.getById(e.getId());
        assertEquals("it content updated", updated.getContent());
        assertEquals(43, updated.getTokens());

        agentMessageMapper.deleteById(e.getId());
        assertNull(agentMessageMapper.getById(e.getId()));
    }

    @Test
    void agentMemory_insertUpdateDelete() {
        AgentMemory e = new AgentMemory();
        e.setScope("GLOBAL");
        e.setSymbol("GOLD");
        e.setStrategyId(3L);
        e.setRegime("RANGE");
        e.setSummary("it summary");
        e.setTags("tagA,tagB");
        e.setSourceTrade(77L);

        agentMemoryMapper.insert(e);
        assertNotNull(e.getId(), "insert 应回填自增 id");

        AgentMemory loaded = agentMemoryMapper.getById(e.getId());
        assertNotNull(loaded);
        assertEquals(e.getScope(), loaded.getScope());
        assertEquals(e.getSymbol(), loaded.getSymbol());
        assertEquals(e.getStrategyId(), loaded.getStrategyId());
        assertEquals(e.getRegime(), loaded.getRegime());
        assertEquals(e.getSummary(), loaded.getSummary());
        assertEquals(e.getTags(), loaded.getTags());
        assertEquals(e.getSourceTrade(), loaded.getSourceTrade());

        loaded.setSummary("it summary updated");
        loaded.setRegime("TREND");
        agentMemoryMapper.update(loaded);
        AgentMemory updated = agentMemoryMapper.getById(e.getId());
        assertEquals("it summary updated", updated.getSummary());
        assertEquals("TREND", updated.getRegime());

        agentMemoryMapper.deleteById(e.getId());
        assertNull(agentMemoryMapper.getById(e.getId()));
    }
}