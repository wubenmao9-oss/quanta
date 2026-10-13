package com.benmao.quanta.memory;

import com.benmao.quanta.common.Result;
import com.benmao.quanta.entity.AgentMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MemoryController 单测：检索与强制总结的路由转发。 */
class MemoryControllerTest {

    private MemoryService service;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(MemoryService.class);
        controller = new MemoryController(service);
    }

    @Test
    @DisplayName("检索：按 scope/symbol/strategyId/tag/limit 转发到 search")
    void listDelegates() {
        AgentMemory card = new AgentMemory();
        card.setId(1L);
        when(service.search("STRATEGY", "GOLD", 13L, "trend", 5)).thenReturn(List.of(card));

        Result<List<AgentMemory>> r = controller.list("STRATEGY", "GOLD", 13L, "trend", 5);

        assertEquals(0, r.getCode());
        assertEquals(1, r.getData().size());
        verify(service).search("STRATEGY", "GOLD", 13L, "trend", 5);
    }

    @Test
    @DisplayName("强制总结：转发到 summarize")
    void summarizeDelegates() {
        AgentMemory card = new AgentMemory();
        card.setId(9L);
        when(service.summarize(eq(7L), eq("GOLD"), eq(13L), eq("TREND"))).thenReturn(card);

        Result<AgentMemory> r = controller.summarize(7L, "GOLD", 13L, "TREND");

        assertEquals(9L, r.getData().getId());
        verify(service).summarize(7L, "GOLD", 13L, "TREND");
    }
}
