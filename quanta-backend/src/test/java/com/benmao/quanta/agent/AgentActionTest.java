package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 契约动作枚举单测（M10 验收①：OPEN/CLOSE/MODIFY/PLAN/WAIT/NOOP 生效）。 */
class AgentActionTest {

    @Test
    @DisplayName("宽松解析：忽略大小写与空白")
    void parsesLoose() {
        assertEquals(AgentAction.OPEN, AgentAction.parse("open"));
        assertEquals(AgentAction.WAIT, AgentAction.parse("  Wait "));
        assertEquals(AgentAction.NOOP, AgentAction.parse("NOOP"));
        assertEquals(AgentAction.PLAN, AgentAction.parse("plan"));
    }

    @Test
    @DisplayName("非法动作被拒并回执合法枚举")
    void rejectsIllegal() {
        BizException e = assertThrows(BizException.class, () -> AgentAction.parse("HACK"));
        assertTrue(e.getMessage().contains("OPEN"));
        assertTrue(e.getMessage().contains("NOOP"));
        assertThrows(BizException.class, () -> AgentAction.parse(""));
        assertThrows(BizException.class, () -> AgentAction.parse(null));
    }

    @Test
    @DisplayName("写动作判定：仅 OPEN/CLOSE/MODIFY 为写动作")
    void marksWriteActions() {
        assertTrue(AgentAction.OPEN.isWrite());
        assertTrue(AgentAction.CLOSE.isWrite());
        assertTrue(AgentAction.MODIFY.isWrite());
        assertFalse(AgentAction.PLAN.isWrite());
        assertFalse(AgentAction.WAIT.isWrite());
        assertFalse(AgentAction.NOOP.isWrite());
    }

    @Test
    @DisplayName("names() 覆盖全部 6 个动作")
    void namesContainsAll() {
        String names = AgentAction.names();
        for (AgentAction a : AgentAction.values()) {
            assertTrue(names.contains(a.name()), "应含 " + a.name());
        }
        assertEquals(6, AgentAction.values().length);
    }
}
