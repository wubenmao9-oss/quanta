package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 决策解析单测（M10）：容错剥离代码围栏/解释文字，动作非法即拒，缺 confidence 视为 0。 */
class AgentDecisionParserTest {

    private final AgentDecisionParser parser = new AgentDecisionParser(new ObjectMapper());

    @Test
    @DisplayName("解析纯 JSON")
    void parsesPlainJson() {
        AgentDecision d = parser.parse("{\"action\":\"OPEN\",\"confidence\":0.82,\"reason\":\"金叉\","
                + "\"params\":{\"side\":\"BUY\",\"sl\":4200.5}}");
        assertEquals(AgentAction.OPEN, d.action());
        assertEquals(0.82, d.confidence(), 1e-9);
        assertEquals("金叉", d.reason());
        assertEquals("BUY", d.params().get("side"));
    }

    @Test
    @DisplayName("容错剥离 ```json 代码围栏")
    void parsesFencedJson() {
        AgentDecision d = parser.parse("```json\n{\"action\":\"WAIT\",\"confidence\":0.7,\"reason\":\"观察\"}\n```");
        assertEquals(AgentAction.WAIT, d.action());
        assertEquals(0.7, d.confidence(), 1e-9);
    }

    @Test
    @DisplayName("容错抓取夹杂解释文字中的 JSON 对象")
    void parsesJsonWithProse() {
        AgentDecision d = parser.parse("我的判断如下：\n{\"action\":\"PLAN\",\"confidence\":0.6,\"reason\":\"等回踩\","
                + "\"params\":{\"planType\":\"PRICE\",\"targetPrice\":4180.0}}\n以上。");
        assertEquals(AgentAction.PLAN, d.action());
        assertEquals("PRICE", d.params().get("planType"));
    }

    @Test
    @DisplayName("缺少 confidence → 视为 0（后续会被门控降级，fail-closed）")
    void missingConfidenceDefaultsZero() {
        AgentDecision d = parser.parse("{\"action\":\"OPEN\",\"reason\":\"无置信度\"}");
        assertEquals(0.0, d.confidence(), 1e-9);
    }

    @Test
    @DisplayName("confidence 越界被 clamp 到 [0,1]")
    void clampsConfidence() {
        assertEquals(1.0, parser.parse("{\"action\":\"WAIT\",\"confidence\":3.2}").confidence(), 1e-9);
        assertEquals(0.0, parser.parse("{\"action\":\"WAIT\",\"confidence\":-1}").confidence(), 1e-9);
    }

    @Test
    @DisplayName("动作非法 → 抛错（回执合法枚举）")
    void invalidActionThrows() {
        BizException e = assertThrows(BizException.class,
                () -> parser.parse("{\"action\":\"EXPLODE\",\"confidence\":0.9}"));
        assertTrue(e.getMessage().contains("CLOSE"));
    }

    @Test
    @DisplayName("缺少 action / 非 JSON 对象 / 空文本 → 抛错")
    void malformedThrows() {
        assertThrows(BizException.class, () -> parser.parse("{\"confidence\":0.9}"));
        assertThrows(BizException.class, () -> parser.parse("\"just a string\""));
        assertThrows(BizException.class, () -> parser.parse("无 JSON"));
        assertThrows(BizException.class, () -> parser.parse("   "));
        assertThrows(BizException.class, () -> parser.parse(null));
    }
}
