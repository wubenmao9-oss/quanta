package com.benmao.quanta.bridge;

import com.benmao.mt5.Mt5;
import com.benmao.mt5.Mt5Config;
import com.benmao.mt5.Mt5ErrorCode;
import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 真环境验收（GOLD 全闭环）：开仓 → 只改 SL（<b>验证 TP 不被清空</b>）→ 平仓。
 *
 * <p>门控：需显式设置环境变量 {@code RUN_MT5_IT=true}，并满足：
 * <ul>
 *   <li>mt5_service(8765) 已启动、MT5 终端已登录且「算法交易」已打开；</li>
 *   <li>开盘时段（休市会返回 10018，本用例会以可读原因失败）。</li>
 * </ul>
 * 运行：{@code $env:RUN_MT5_IT='true'; mvn -pl quanta-backend -am test "-Dtest=Mt5GoldClosedLoopIT" "-Dsurefire.failIfNoSpecifiedTests=false"}
 */
@EnabledIfEnvironmentVariable(named = "RUN_MT5_IT", matches = "true")
class Mt5GoldClosedLoopIT {

    private static final String BASE_URL =
            System.getenv().getOrDefault("QUANTA_MT5_BASE_URL", "http://127.0.0.1:8765");
    /** 一期固定品种的逻辑名；名称映射层会把它解析为券商真实名（FxPro = GOLD）。 */
    private static final String LOGICAL_SYMBOL = "XAUUSD";

    @Test
    @DisplayName("GOLD 全闭环：开仓带 SL/TP → 只改 SL 后 TP 不变 → 平仓")
    void goldClosedLoop() {
        Mt5 mt5 = new Mt5(new Mt5Config(BASE_URL, "GOLD"));
        Long ticket = null;

        try {
            // 1) 连通性
            Map<String, Object> health = mt5.session.health();
            assertTrue(Boolean.TRUE.equals(health.get("ok")), "桥接未就绪: " + health);
            assertTrue(Boolean.TRUE.equals(health.get("terminal_connected")), "MT5 终端未连接: " + health);

            // 2) 名称映射：逻辑名 XAUUSD → 券商名 GOLD，且券商清单里确实有 GOLD
            assertEquals("GOLD", mt5.symbols.resolve(LOGICAL_SYMBOL), "名称映射层未把 XAUUSD 映射为 GOLD");
            assertTrue(mt5.symbols.exists(LOGICAL_SYMBOL), "券商品种清单中不存在 GOLD");

            // 3) 账户 / 报价 / K线 / 持仓 四类数据均可取
            AccountInfo acc = mt5.account.getAccountInfo();
            assertTrue(acc.equity() > 0, "净值应大于 0");
            Quote quote = mt5.market.getQuote(LOGICAL_SYMBOL);
            assertEquals("GOLD", quote.symbol());
            assertTrue(quote.ask() > 0 && quote.bid() > 0);
            List<KlineBar> bars = mt5.market.getKlines(LOGICAL_SYMBOL, "M5", 5);
            assertFalse(bars.isEmpty(), "K 线不应为空");
            List<Position> before = mt5.account.getPositions(LOGICAL_SYMBOL);
            Set<Long> beforeTickets = before.stream().map(Position::ticket).collect(Collectors.toSet());

            // 4) 开仓：0.01 手，SL=买价-10（风险 10 USD），TP=买价+20（盈亏比 1:2）
            double sl = round2(quote.bid() - 10);
            double tp = round2(quote.bid() + 20);
            OrderResult open = mt5.trading.buyMarket(LOGICAL_SYMBOL, 0.01, sl, tp);
            assertTrue(open.success(), "开仓失败: " + Mt5ErrorCode.explain(open.retcode()) + " | " + open);

            // 5) 定位新开的持仓
            Position opened = findNewPosition(mt5, LOGICAL_SYMBOL, beforeTickets);
            assertNotNull(opened, "未找到刚开的持仓");
            ticket = opened.ticket();
            assertEquals(tp, opened.tp(), 0.001, "开仓后 TP 应为 " + tp);
            assertEquals(sl, opened.sl(), 0.001, "开仓后 SL 应为 " + sl);

            // 6) 只改 SL：TP 必须原样保留（原 Python 实现的 modify_sl 会把 TP 清零，此为本用例的核心回归点）
            double newSl = round2(quote.bid() - 5);
            OrderResult modify = mt5.trading.modifySl(LOGICAL_SYMBOL, ticket, newSl);
            assertTrue(modify.success(), "改 SL 失败: " + Mt5ErrorCode.explain(modify.retcode()));
            Position afterModify = mt5.account.findPosition(ticket);
            assertNotNull(afterModify, "改 SL 后应仍能查到持仓");
            assertEquals(newSl, afterModify.sl(), 0.001, "SL 应更新为 " + newSl);
            assertEquals(tp, afterModify.tp(), 0.001, "只改 SL 时 TP 不能被清空/改动");

            // 7) 平仓
            OrderResult close = mt5.trading.closePosition(LOGICAL_SYMBOL, ticket);
            assertTrue(close.success(), "平仓失败: " + Mt5ErrorCode.explain(close.retcode()));
            assertTrue(mt5.account.findPosition(ticket) == null, "平仓后不应再查到该持仓");
        } finally {
            // 兜底清场：用例失败时不留下孤儿持仓
            if (ticket != null && mt5.account.findPosition(ticket) != null) {
                mt5.trading.closePosition(LOGICAL_SYMBOL, ticket);
            }
        }
    }

    @Test
    @DisplayName("错误码归一化：非法手数被拒且原因可读")
    void invalidVolumeGivesReadableReason() {
        Mt5 mt5 = new Mt5(new Mt5Config(BASE_URL, "GOLD"));
        OrderResult bad = mt5.trading.buyMarket(LOGICAL_SYMBOL, -100, 0.0, 0.0);
        assertFalse(bad.success(), "非法手数不应成交");
        String reason = Mt5ErrorCode.explain(bad.retcode());
        assertFalse(reason.contains("详见 MT5 交易返回码表"), "应命中已知返回码的可读文案: " + bad);
        assertTrue(reason.length() > 4, "原因应可读: " + reason);
    }

    private static Position findNewPosition(Mt5 mt5, String symbol, Set<Long> beforeTickets) {
        List<Position> after = mt5.account.getPositions(symbol);
        return after.stream()
                .filter(p -> !beforeTickets.contains(p.ticket()))
                .findFirst()
                .orElse(after.isEmpty() ? null : after.get(after.size() - 1));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}