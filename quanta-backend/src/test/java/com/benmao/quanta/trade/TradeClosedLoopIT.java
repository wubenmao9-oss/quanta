package com.benmao.quanta.trade;

import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易执行真环境验收（M6，GOLD 全闭环）：风控 → 券商开仓 → 流水 FILLED → 平仓 → 流水 CLOSED。
 *
 * <p>门控：需同时设置 {@code RUN_MT5_IT=true} 与 {@code RUN_DB_IT=true}，且 mt5_service(8765) 已启动、
 * MT5 终端已登录、算法交易已打开、处于开盘时段。会下真实 0.01 手测试单（用例结束即平掉）。
 *
 * <p>运行：
 * {@code $env:RUN_MT5_IT='true'; $env:RUN_DB_IT='true'; mvn -pl quanta-backend -am test "-Dtest=TradeClosedLoopIT" "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_MT5_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "RUN_DB_IT", matches = "true")
class TradeClosedLoopIT {

    @Autowired
    private TradingService tradingService;

    @Autowired
    private Mt5BridgeService bridge;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Test
    @DisplayName("真实闭环：开仓成交 ⇔ 流水 FILLED；平仓 ⇔ 流水 CLOSED（含 profit/swap/retcode）")
    void realOpenThenClosePersistsRows() {
        String symbol = bridge.defaultSymbol();
        Quote quote = bridge.quote(symbol);
        double ask = quote.ask();

        // 用微小抖动让 SL/TP 每次不同，避开 R8「同参数 60s 内重复」；风险 = 5 + 抖动 ≤ 6 USD ≤ 预算(≈equity×2%)
        double jitter = Math.round((System.nanoTime() % 90) / 100.0 * 100.0) / 100.0;
        double sl = round2(ask - 5 - jitter);
        double tp = round2(sl + (5 + jitter) * 2);   // 盈亏比恒为 2

        Long ticket = null;
        Long orderId = null;
        try {
            TradeOrder opened = tradingService.openPosition(new TradeCommand(
                    com.benmao.quanta.risk.RiskAction.OPEN, symbol, RiskSide.BUY,
                    new BigDecimal("0.01"), null, BigDecimal.valueOf(sl), BigDecimal.valueOf(tp),
                    null, null, null, null, "it-open-" + System.nanoTime()));

            // 下单成功 ⇔ 流水入库（同事务）
            orderId = opened.getId();
            assertNotNull(orderId, "开仓成功必须落库并回填 id");
            assertEquals("FILLED", opened.getStatus());
            assertEquals(10009, opened.getRetcode(), "成交返回码应为 10009(成功)");
            assertNotNull(opened.getTicket(), "应定位到新开持仓 ticket");
            ticket = opened.getTicket();

            TradeOrder fromDb = tradeOrderMapper.getById(orderId);
            assertNotNull(fromDb, "开仓后必须能查到流水");
            assertEquals("FILLED", fromDb.getStatus());
            assertEquals(ticket, fromDb.getTicket());
            assertEquals(symbol, fromDb.getSymbol());
            assertEquals("BUY", fromDb.getCmd());

            // 平仓
            TradeOrder closed = tradingService.closePosition(new TradeCommand(
                    com.benmao.quanta.risk.RiskAction.CLOSE, symbol, null, null, null, null, null, null,
                    ticket, null, null, "it-close-" + System.nanoTime()));
            assertEquals("CLOSED", closed.getStatus());
            assertEquals(10009, closed.getRetcode());
            assertNotNull(closed.getProfit(), "平仓应落 profit");
            assertNotNull(closed.getSwap(), "平仓应落 swap（隔夜费，持仓期间取自券商）");
            assertNotNull(closed.getClosePrice(), "平仓应落 close_price");
            assertNotNull(closed.getCloseTime(), "平仓应落 close_time");

            TradeOrder closedDb = tradeOrderMapper.getById(orderId);
            assertEquals("CLOSED", closedDb.getStatus(), "平仓后流水应更新为 CLOSED");
            assertEquals(10009, closedDb.getRetcode());
            ticket = null;   // 已平，无需清场
        } finally {
            cleanUp(symbol, ticket);
        }
    }

    /** 兜底清场：用例中途失败时不留下孤儿持仓。 */
    private void cleanUp(String symbol, Long ticket) {
        if (ticket == null) {
            return;
        }
        List<Position> positions = bridge.positions(symbol);
        boolean stillOpen = positions.stream().anyMatch(p -> p.ticket() == ticket);
        if (stillOpen) {
            bridge.closePosition(symbol, ticket);
            assertTrue(bridge.positions(symbol).stream().noneMatch(p -> p.ticket() == ticket), "清场失败");
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
