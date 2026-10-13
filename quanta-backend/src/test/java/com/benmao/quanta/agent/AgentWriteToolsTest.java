package com.benmao.quanta.agent;

import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.entity.TradePlan;
import com.benmao.quanta.mapper.TradePlanMapper;
import com.benmao.quanta.risk.RiskSide;
import com.benmao.quanta.trade.TradeCommand;
import com.benmao.quanta.trade.TradingService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写路径单测（M10 验收④：写工具绕过风控的尝试全部失败）。
 *
 * <p>证明方式：
 * <ol>
 *   <li>{@link AgentWriteTools} 的写操作<b>只</b>转交 {@link TradingService}（其每个方法必过 RiskGuard），
 *       风控拒绝会原样抛出；</li>
 *   <li>结构证明：写工具不持有桥接层等其它协作者，也无任何「直接下单」的旁路；</li>
 *   <li>写工具<b>不</b>标注 {@code @Tool}，不暴露给大模型。</li>
 * </ol>
 */
class AgentWriteToolsTest {

    private final TradingService tradingService = mock(TradingService.class);
    private final TradePlanMapper planMapper = mock(TradePlanMapper.class);
    private final AgentWriteTools tools = new AgentWriteTools(tradingService, planMapper);

    private static TradeOrder order(String status) {
        TradeOrder o = new TradeOrder();
        o.setTicket(7001L);
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("开仓：装配为 OPEN 命令并转交 TradingService（必经风控）")
    void openDelegatesToTradingService() {
        when(tradingService.openPosition(any())).thenReturn(order("FILLED"));

        TradeOrder out = tools.open("GOLD", "buy", new BigDecimal("0.01"), new BigDecimal("4200.10"),
                new BigDecimal("4230.10"), "SOFT", 7L, "t1");

        ArgumentCaptor<TradeCommand> c = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).openPosition(c.capture());
        TradeCommand cmd = c.getValue();
        assertEquals("OPEN", cmd.action().name());
        assertEquals(RiskSide.BUY, cmd.side());
        assertEquals(0, new BigDecimal("4200.10").compareTo(cmd.sl()));
        assertEquals(7L, cmd.sessionId());
        assertEquals("t1", cmd.traceId());
        assertEquals("FILLED", out.getStatus());
    }

    @Test
    @DisplayName("风控拒绝：写工具原样抛出（无旁路可绕过 RiskGuard）")
    void riskRejectionPropagates() {
        when(tradingService.openPosition(any())).thenThrow(new BizException("风控拒绝[R5]：开仓必须设置止损"));

        BizException e = assertThrows(BizException.class, () -> tools.open("GOLD", "BUY",
                new BigDecimal("0.01"), null, null, null, 7L, "t1"));
        assertTrue(e.getMessage().contains("风控拒绝"));
    }

    @Test
    @DisplayName("平仓与改仓：同样只转交 TradingService")
    void closeAndModifyDelegate() {
        when(tradingService.closePosition(any())).thenReturn(order("CLOSED"));
        when(tradingService.modifySlTp(any())).thenReturn(order("FILLED"));

        tools.close("GOLD", 7001L, 7L, "t1");
        ArgumentCaptor<TradeCommand> cc = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).closePosition(cc.capture());
        assertEquals("CLOSE", cc.getValue().action().name());
        assertEquals(7001L, cc.getValue().ticket());

        tools.modify("GOLD", 7001L, new BigDecimal("4201"), null, 7L, "t1");
        ArgumentCaptor<TradeCommand> cm = ArgumentCaptor.forClass(TradeCommand.class);
        verify(tradingService).modifySlTp(cm.capture());
        assertEquals("MODIFY", cm.getValue().action().name());
        assertEquals(7001L, cm.getValue().ticket());
    }

    @Test
    @DisplayName("登记计划：落 PENDING，无需风控；非法 planType/缺 targetPrice 被拒")
    void planRegistersPendingAndValidates() {
        TradePlan p = tools.plan(7L, "PRICE", new BigDecimal("4180.00"), "跌破 4170 失效", null);
        ArgumentCaptor<TradePlan> c = ArgumentCaptor.forClass(TradePlan.class);
        verify(planMapper).insert(c.capture());
        assertEquals("PENDING", c.getValue().getStatus());
        assertEquals("PRICE", c.getValue().getPlanType());
        assertEquals("PENDING", p.getStatus());

        assertThrows(BizException.class, () -> tools.plan(7L, "XXX", null, null, null));
        assertThrows(BizException.class, () -> tools.plan(7L, "PRICE", null, null, null));
    }

    @Test
    @DisplayName("结构证明：写工具的唯一协作者是 TradingService / TradePlanMapper，不持有桥接层")
    void holdsNoBridgeReference() {
        for (Field f : AgentWriteTools.class.getDeclaredFields()) {
            if (f.isSynthetic()) {
                continue;
            }
            assertTrue(f.getType() == TradingService.class || f.getType() == TradePlanMapper.class,
                    "写工具不应持有其它协作者（否则可能绕过风控）：" + f.getType().getName());
        }
    }

    @Test
    @DisplayName("写工具不暴露为 @Tool（大模型无法直接调用）")
    void notExposedAsTool() {
        for (Method m : AgentWriteTools.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(Tool.class), "写方法不得标注 @Tool：" + m.getName());
        }
    }
}
