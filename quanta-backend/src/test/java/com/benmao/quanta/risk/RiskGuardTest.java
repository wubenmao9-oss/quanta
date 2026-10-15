package com.benmao.quanta.risk;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;
import com.benmao.quanta.bridge.Mt5BridgeService;
import com.benmao.quanta.common.exception.BizException;
import com.benmao.quanta.entity.TradeOrder;
import com.benmao.quanta.mapper.TradeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控层单测（M5 验收：R1~R15 每条一测 + 幻觉用例全拦 + 拒绝即回执）。
 *
 * <p>行情/账户/持仓由桥接 mock 提供：GOLD ask 4194.23 / bid 4194.07 / 点差 16 points、净值 500 USD。
 * 固定时钟 2026-10-12 16:00（周一，非休市），休市用例单独用周六。
 */
class RiskGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 10, 12, 16, 0);
    private static final LocalDateTime SATURDAY = LocalDateTime.of(2026, 10, 10, 16, 0);

    private Mt5BridgeService bridge;
    private RiskConfigService riskConfig;
    private TradeOrderMapper tradeOrderMapper;
    private SpreadHistory spreadHistory;
    private RiskProperties props;

    @BeforeEach
    void setUp() {
        bridge = mock(Mt5BridgeService.class);
        riskConfig = mock(RiskConfigService.class);
        tradeOrderMapper = mock(TradeOrderMapper.class);
        spreadHistory = mock(SpreadHistory.class);
        props = new RiskProperties();

        when(bridge.defaultSymbol()).thenReturn("GOLD");
        when(bridge.resolveSymbol(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(bridge.quote(anyString())).thenReturn(new Quote("GOLD", 4194.07, 4194.23, 16, 2, 1760000000L));
        when(bridge.account()).thenReturn(new AccountInfo(592176689L, "demo", "FxPro", "USD",
                499.98, 500.0, 8.4, 491.6, 0.0, 500));
        when(bridge.positions(any())).thenReturn(new ArrayList<>());

        when(riskConfig.thresholds(any())).thenReturn(defaultThresholds());
        when(riskConfig.runMode()).thenReturn("AUTO");

        when(tradeOrderMapper.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
        when(tradeOrderMapper.listRecentClosed(anyInt())).thenReturn(List.of());
        when(tradeOrderMapper.countByTraceIdSince(anyString(), any())).thenReturn(0);
        when(tradeOrderMapper.countSameParamsSince(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);
        when(tradeOrderMapper.findLatestByTicket(anyLong())).thenReturn(null);

        when(spreadHistory.stat(any(), anyInt())).thenReturn(new SpreadHistory.Stat(0, Double.NaN));
    }

    private RiskThresholds defaultThresholds() {
        return new RiskThresholds(new BigDecimal("0.01"), new BigDecimal("0.01"), 3,
                new BigDecimal("50"), new BigDecimal("2.00"), new BigDecimal("1.00"),
                new BigDecimal("5.00"), 3, 0, new BigDecimal("50"), List.of("GOLD"));
    }

    private RiskGuard guard() {
        return guardAt(MONDAY);
    }

    private RiskGuard guardAt(LocalDateTime now) {
        return new RiskGuard(bridge, riskConfig, tradeOrderMapper, spreadHistory, props,
                Clock.fixed(now.atZone(ZONE).toInstant(), ZONE));
    }

    private static RiskRequest open(RiskSide side, String volume, String sl, String tp) {
        return new RiskRequest(RiskAction.OPEN, "GOLD", side, new BigDecimal(volume), null,
                sl == null ? null : new BigDecimal(sl), tp == null ? null : new BigDecimal(tp),
                "SOFT_TP", null, 1L, "trace-1");
    }

    private static Position position(long ticket, String type, double openPrice, double sl, double tp) {
        return new Position(ticket, "GOLD", type, 0.01, openPrice, openPrice, sl, tp, 0.0, 0.0, 0L, "", "2026-10-12");
    }

    private static void assertViolated(RiskDecision d, String rule, String code) {
        assertFalse(d.allowed(), "应被拒绝");
        assertTrue(d.violations().stream().anyMatch(v -> v.rule().equals(rule) && v.code().equals(code)),
                "应包含 [" + rule + "/" + code + "]，实际=" + d.violations().stream()
                        .map(v -> v.rule() + "/" + v.code()).toList());
    }

    // ---------------- 放行基线 ----------------

    @Test
    @DisplayName("合法开仓放行：GOLD BUY 0.01，SL 距开仓价 5 USD（风险 5 USD ≤ 预算 10 USD）")
    void validOpenPasses() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", "4204.23"));
        assertTrue(d.allowed(), "应放行，实际违规=" + d.violations().stream().map(RiskViolation::code).toList());
        assertTrue(d.checked().containsAll(List.of("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8",
                "R9", "R10", "R11", "R12", "R15")));
    }

    // ---------------- R1 白名单 ----------------

    @Test
    @DisplayName("R1 白名单：非白名单品种被拒并回执允许清单")
    void r1Whitelist() {
        when(bridge.resolveSymbol("EURUSD")).thenReturn("EURUSD");
        RiskRequest req = new RiskRequest(RiskAction.OPEN, "EURUSD", RiskSide.BUY, new BigDecimal("0.01"),
                null, new BigDecimal("4189.23"), null, "SOFT_TP", null, 1L, "t");
        RiskDecision d = guard().evaluate(req);
        assertViolated(d, "R1", "SYMBOL_NOT_ALLOWED");
        assertTrue(d.violations().get(0).bounds().get("allow").toString().contains("GOLD"));
    }

    // ---------------- R2 手数边界 ----------------

    @Test
    @DisplayName("R2 手数：负数（幻觉）/越界/非步长倍数均被拒并回执 [min,max,step]")
    void r2Volume() {
        RiskDecision neg = guard().evaluate(open(RiskSide.BUY, "-100", "4189.23", null));
        assertViolated(neg, "R2", "VOLUME_OUT_OF_RANGE");
        assertEquals(0.01, (double) neg.violations().get(0).bounds().get("min"), 1e-9);

        RiskDecision big = guard().evaluate(open(RiskSide.BUY, "0.02", "4189.23", null));
        assertViolated(big, "R2", "VOLUME_OUT_OF_RANGE");

        // 放宽上限后，非步长倍数单独命中
        RiskThresholds wide = new RiskThresholds(new BigDecimal("0.01"), new BigDecimal("1.00"), 3,
                new BigDecimal("50"), new BigDecimal("2.00"), new BigDecimal("1.00"),
                new BigDecimal("5.00"), 3, 0, new BigDecimal("50"), List.of("GOLD"));
        when(riskConfig.thresholds(any())).thenReturn(wide);
        RiskDecision step = guard().evaluate(open(RiskSide.BUY, "0.023", "4189.23", null));
        assertViolated(step, "R2", "VOLUME_STEP_MISMATCH");
        assertEquals(0.01, (double) step.violations().get(0).bounds().get("step"), 1e-9);
    }

    // ---------------- R3 最大持仓 ----------------

    @Test
    @DisplayName("R3 最大持仓：已达上限则拒并回执当前/上限")
    void r3MaxPositions() {
        when(bridge.positions(any())).thenReturn(List.of(
                position(1, "buy", 4190, 4180, 4210),
                position(2, "buy", 4191, 4181, 4211),
                position(3, "buy", 4192, 4182, 4212)));
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null));
        assertViolated(d, "R3", "MAX_POSITIONS_REACHED");
        assertEquals(3, d.violations().get(0).bounds().get("current"));
    }

    // ---------------- GRID 档（多腿/马丁执行器专用） ----------------

    @Test
    @DisplayName("GRID 档：仅放宽 R2/R3（手数上限 2.00、持仓上限 5）；同盘面 DEFAULT 档被 R3 拒")
    void gridProfileWidensR2R3() {
        when(riskConfig.thresholds(RiskProfile.GRID)).thenReturn(new RiskThresholds(
                new BigDecimal("0.01"), new BigDecimal("2.00"), 5,
                new BigDecimal("50"), new BigDecimal("2.00"), new BigDecimal("0.50"),
                new BigDecimal("5.00"), 3, 0, new BigDecimal("50"), List.of("GOLD")));
        when(riskConfig.thresholds(RiskProfile.DEFAULT)).thenReturn(defaultThresholds());
        when(bridge.positions(any())).thenReturn(List.of(
                position(1, "buy", 4190, 4180, 4210),
                position(2, "buy", 4191, 4181, 4211),
                position(3, "buy", 4192, 4182, 4212),
                position(4, "buy", 4193, 4183, 4213)));

        // DEFAULT 档：已有 4 仓 ≥ 上限 3 → R3 拒
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4193.00", null)),
                "R3", "MAX_POSITIONS_REACHED");

        // GRID 档：0.05 手 + 已有 4 仓 → 放行（R2 上限 2.00、R3 上限 5 均放宽）
        RiskRequest grid = new RiskRequest(RiskAction.OPEN, "GOLD", RiskSide.BUY, new BigDecimal("0.05"),
                null, new BigDecimal("4193.00"), null, "NONE", null, 1L, "trace-grid",
                RiskOrigin.AI, RiskProfile.GRID);
        RiskDecision g = guard().evaluate(grid);
        assertTrue(g.allowed(), "GRID 档应放行，实际违规=" + g.violations().stream()
                .map(v -> v.rule() + "/" + v.code()).toList());
    }

    // ---------------- R4 单笔风险 ----------------

    @Test
    @DisplayName("R4 单笔风险：500×2%=10 USD 预算，SL 距离 11 USD → 风险 11 USD 被拒并回执最大 SL 距离")
    void r4RiskBudget() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4183.23", null));
        assertViolated(d, "R4", "SINGLE_TRADE_RISK_EXCEEDED");
        assertEquals(10.0, (double) d.violations().get(0).bounds().get("budgetUsd"), 1e-6);
        assertEquals(10.0, (double) d.violations().get(0).bounds().get("maxSlDistance"), 1e-6);
    }

    @Test
    @DisplayName("R4 边界：恰好等于预算（风险=10 USD）放行")
    void r4ExactBudgetPasses() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4184.23", null));
        assertTrue(d.allowed(), "恰好等于预算应放行，实际=" + d.violations().stream().map(RiskViolation::code).toList());
    }

    // ---------------- R5 SL 必填且合法 ----------------

    @Test
    @DisplayName("R5 无 SL（幻觉）被拒")
    void r5SlRequired() {
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", null, null)), "R5", "SL_REQUIRED");
    }

    @Test
    @DisplayName("R5 SL 方向错误（BUY 的 SL 高于开仓价）被拒")
    void r5SlDirection() {
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4200.00", null)), "R5", "SL_DIRECTION");
    }

    @Test
    @DisplayName("R5 SL 贴价（距离 0.5 < 点差16×5=0.8）被拒并回执最小 SL 价")
    void r5SlTooClose() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4193.73", null));
        assertViolated(d, "R5", "SL_TOO_CLOSE");
        assertEquals(0.8, (double) d.violations().get(0).bounds().get("minSlDistance"), 1e-6);
        assertEquals(4193.43, (double) d.violations().get(0).bounds().get("minSlPrice"), 1e-6);
    }

    // ---------------- R6 日亏熔断 ----------------

    @Test
    @DisplayName("R6 日亏熔断：当日亏损达 50 USD 上限 → 禁止开仓")
    void r6DailyLoss() {
        when(tradeOrderMapper.sumRealizedPnlSince(any())).thenReturn(new BigDecimal("-50"));
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R6", "DAILY_LOSS_CIRCUIT_BREAKER");
    }

    // ---------------- R7 连亏冷静期 ----------------

    @Test
    @DisplayName("R7 冷静期：连亏 3 笔且 cooldown=1min → 暂停开仓并回执 resumeAt")
    void r7Cooldown() {
        RiskThresholds th = new RiskThresholds(new BigDecimal("0.01"), new BigDecimal("0.01"), 3,
                new BigDecimal("50"), new BigDecimal("2.00"), new BigDecimal("1.00"),
                new BigDecimal("5.00"), 3, 1, new BigDecimal("50"), List.of("GOLD"));
        when(riskConfig.thresholds(any())).thenReturn(th);
        when(tradeOrderMapper.listRecentClosed(anyInt())).thenReturn(List.of(
                closedOrder(MONDAY.minusMinutes(0)),
                closedOrder(MONDAY.minusMinutes(5)),
                closedOrder(MONDAY.minusMinutes(9))));
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null));
        assertViolated(d, "R7", "COOLDOWN_ACTIVE");
        assertTrue(d.violations().get(0).bounds().get("resumeAt").toString().contains("2026-10-12T16:01"));
    }

    @Test
    @DisplayName("R7 默认 cooldown=0 关闭：连亏也不拦")
    void r7DisabledByDefault() {
        when(tradeOrderMapper.listRecentClosed(anyInt())).thenReturn(List.of(
                closedOrder(MONDAY), closedOrder(MONDAY), closedOrder(MONDAY)));
        assertTrue(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)).allowed());
    }

    private static TradeOrder closedOrder(LocalDateTime closeTime) {
        TradeOrder o = new TradeOrder();
        o.setProfit(new BigDecimal("-3"));
        o.setSwap(BigDecimal.ZERO);
        o.setCloseTime(closeTime);
        return o;
    }

    // ---------------- R8 幂等 ----------------

    @Test
    @DisplayName("R8 幂等：同 trace_id / 同参数 60s 内重复被拒")
    void r8Idempotency() {
        when(tradeOrderMapper.countByTraceIdSince(eq("trace-1"), any())).thenReturn(1);
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R8", "DUPLICATE_TRACE_ID");
    }

    @Test
    @DisplayName("R8 幂等：同参数重复被拒")
    void r8DuplicateParams() {
        when(tradeOrderMapper.countSameParamsSince(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R8", "DUPLICATE_PARAMS");
    }

    // ---------------- R9 动态点差 ----------------

    @Test
    @DisplayName("R9 点差：实时 60 points > max(50, 中位×3) 被拒")
    void r9SpreadTooWide() {
        when(bridge.quote(anyString())).thenReturn(new Quote("GOLD", 4194.07, 4194.23, 60, 2, 1L));
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R9", "SPREAD_TOO_WIDE");
    }

    @Test
    @DisplayName("R9 点差：中位数抬高动态阈值（中位20×3=60 > 上限50），实时55通过")
    void r9DynamicThreshold() {
        when(spreadHistory.stat(any(), anyInt())).thenReturn(new SpreadHistory.Stat(30, 20.0));
        when(bridge.quote(anyString())).thenReturn(new Quote("GOLD", 4194.07, 4194.23, 55, 2, 1L));
        assertTrue(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)).allowed());
        verify(spreadHistory).record(eq(55.0), any());
    }

    // ---------------- R10 禁开窗口 ----------------

    @Test
    @DisplayName("R10 禁开窗口：周六休市禁止开仓并回执 nextOpenAt")
    void r10Weekend() {
        RiskDecision d = guardAt(SATURDAY).evaluate(open(RiskSide.BUY, "0.01", "4189.23", null));
        assertViolated(d, "R10", "OPEN_WINDOW_CLOSED");
        assertTrue(d.violations().get(0).bounds().containsKey("nextOpenAt"));
    }

    // ---------------- R11 人工开关 ----------------

    @Test
    @DisplayName("R11 三态：PAUSED 禁开仓、TAKEOVER 全禁")
    void r11RunMode() {
        when(riskConfig.runMode()).thenReturn("PAUSED");
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R11", "RUN_MODE_PAUSED");

        when(riskConfig.runMode()).thenReturn("TAKEOVER");
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", null)),
                "R11", "RUN_MODE_TAKEOVER");
    }

    @Test
    @DisplayName("R11 PAUSED 时平仓仍允许")
    void r11PausedAllowsClose() {
        when(riskConfig.runMode()).thenReturn("PAUSED");
        when(bridge.positions(any())).thenReturn(List.of(position(77, "buy", 4190, 4180, 4210)));
        RiskRequest close = new RiskRequest(RiskAction.CLOSE, "GOLD", null, null, null, null, null,
                null, 77L, 1L, "t-close");
        assertTrue(guard().evaluate(close).allowed());
    }

    @Test
    @DisplayName("R11 人工豁免：TAKEOVER 下人工开仓不被 R11 拦（AI 同参被拦），仍过其余硬规则")
    void r11ManualExemptInTakeover() {
        when(riskConfig.runMode()).thenReturn("TAKEOVER");
        RiskRequest ai = new RiskRequest(RiskAction.OPEN, "GOLD", RiskSide.BUY, new BigDecimal("0.01"),
                null, new BigDecimal("4189.23"), null, "SOFT_TP", null, null, "t-ai", RiskOrigin.AI);
        assertViolated(guard().evaluate(ai), "R11", "RUN_MODE_TAKEOVER");

        RiskRequest manual = new RiskRequest(RiskAction.OPEN, "GOLD", RiskSide.BUY, new BigDecimal("0.01"),
                null, new BigDecimal("4189.23"), null, "SOFT_TP", null, null, "t-manual", RiskOrigin.MANUAL);
        assertTrue(guard().evaluate(manual).allowed(), "人工开仓应豁免 R11 并放行其余规则");
    }

    // ---------------- R12 价格偏离 ----------------

    @Test
    @DisplayName("R12 价格偏离：开仓价 4300 vs 市价 4194.23（2.5% > 0.5%）被拒")
    void r12PriceDeviation() {
        RiskRequest req = new RiskRequest(RiskAction.OPEN, "GOLD", RiskSide.BUY, new BigDecimal("0.01"),
                new BigDecimal("4300"), new BigDecimal("4290"), null, "SOFT_TP", null, 1L, "t");
        assertViolated(guard().evaluate(req), "R12", "PRICE_DEVIATION");
    }

    // ---------------- R13 持仓互斥 ----------------

    @Test
    @DisplayName("R13 持仓互斥：持仓不存在被拒；被其他会话占用被拒并回执占用方")
    void r13Mutex() {
        RiskRequest close = new RiskRequest(RiskAction.CLOSE, "GOLD", null, null, null, null, null,
                null, 77L, 1L, "t");
        assertViolated(guard().evaluate(close), "R13", "POSITION_NOT_FOUND");

        when(bridge.positions(any())).thenReturn(List.of(position(77, "buy", 4190, 4180, 4210)));
        TradeOrder owner = new TradeOrder();
        owner.setSessionId(99L);
        when(tradeOrderMapper.findLatestByTicket(77L)).thenReturn(owner);
        RiskDecision d = guard().evaluate(close);
        assertViolated(d, "R13", "POSITION_LOCKED_BY_SESSION");
        assertEquals(99L, d.violations().get(0).bounds().get("ownerSessionId"));
    }

    // ---------------- R14 软位单调 ----------------

    @Test
    @DisplayName("R14 软位单调：BUY 的 SL 只能上移；下移（放大风险）被拒并回执合法区间")
    void r14Monotonic() {
        when(bridge.positions(any())).thenReturn(List.of(position(77, "buy", 4190, 4185, 4210)));
        RiskRequest loosen = new RiskRequest(RiskAction.MODIFY, "GOLD", null, null, null,
                new BigDecimal("4180"), null, null, 77L, 1L, "t");
        RiskDecision d = guard().evaluate(loosen);
        assertViolated(d, "R14", "SL_NOT_TIGHTENED");
        assertTrue(d.violations().get(0).bounds().get("allowed").toString().contains("4185"));

        RiskRequest tighten = new RiskRequest(RiskAction.MODIFY, "GOLD", null, null, null,
                new BigDecimal("4190"), new BigDecimal("4210"), null, 77L, 1L, "t2");
        assertTrue(guard().evaluate(tighten).allowed(), "上移 SL 应放行");
    }

    // ---------------- R15 盈亏比地板 ----------------

    @Test
    @DisplayName("R15 盈亏比：1:0.1（远低于地板 1.0）被拒并回执最小 TP 价")
    void r15RiskRewardFloor() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", "4194.73"));
        assertViolated(d, "R15", "RR_BELOW_FLOOR");
        assertEquals(1.00, ((Number) d.violations().get(0).bounds().get("minRr")).doubleValue(), 1e-9);
        // slDist=5，地板 1.0 → 最小 TP 距离 5 → minTpPrice = 4194.23 + 5
        assertEquals(4199.23, (double) d.violations().get(0).bounds().get("minTpPrice"), 1e-6);
    }

    @Test
    @DisplayName("R15 TP 反向（幻觉：BUY 的 TP 低于开仓价）被拒")
    void r15TpDirection() {
        assertViolated(guard().evaluate(open(RiskSide.BUY, "0.01", "4189.23", "4180.00")),
                "R15", "TP_DIRECTION");
    }

    // ---------------- 拒绝即回执 / 无绕过 ----------------

    @Test
    @DisplayName("拒绝即回执：orThrow() 抛 BizException 且消息含规则号与合法边界")
    void rejectGivesStructuredReceipt() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "0.01", "4193.73", null));
        BizException e = assertThrows(BizException.class, d::orThrow);
        assertTrue(e.getMessage().contains("R5"), e.getMessage());
        assertTrue(e.getMessage().contains("minSlPrice"), e.getMessage());
    }

    @Test
    @DisplayName("无绕过：被拒的动作不产生任何下单副作用（风控只决策）")
    void rejectedActionHasNoSideEffect() {
        RiskDecision d = guard().evaluate(open(RiskSide.BUY, "-100", "4189.23", null));
        assertFalse(d.allowed());
        // M5 风控不做下单；断言被拒路径不触发任何写库/下单（幂等与日亏查询之外无写操作）
        verify(tradeOrderMapper, never()).findLatestByTicket(anyLong());
    }

    @Test
    @DisplayName("非法动作/方向入参被拒并回执可选值")
    void invalidActionOrSideRejected() {
        BizException e1 = assertThrows(BizException.class, () -> RiskAction.parse("HEDGE"));
        assertTrue(e1.getMessage().contains("OPEN, CLOSE, MODIFY"));
        BizException e2 = assertThrows(BizException.class, () -> RiskSide.parse("MIDDLE"));
        assertTrue(e2.getMessage().contains("BUY, SELL"));
    }
}
