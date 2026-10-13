package com.benmao.quanta.strategy;

import com.benmao.quanta.strategy.StrategyGateReport.Criterion;
import com.benmao.quanta.strategy.StrategyGateReport.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 门禁纯判据求值器单测：逐条判据的通过/拒绝边界。 */
class StrategyGateEvaluatorTest {

    private static StrategyProperties.Gate cfg() {
        return new StrategyProperties().getGate();
    }

    private static Segment seg(int trades, double winRate, double pf, double dd, double ddPct,
                               double net, double expectancy) {
        return new Segment(1L, trades, winRate, pf, dd, ddPct, net, expectancy);
    }

    /** 一个"全过"的基准：R=2.0 → 需胜率 > 33.33%。 */
    private static Segment goodFull() {
        return seg(200, 45.0, 1.6, 60.0, 12.0, 120.0, 0.6);
    }

    private static Map<String, Criterion> byId(StrategyGateEvaluator.Evaluation ev) {
        return ev.criteria().stream().collect(Collectors.toMap(Criterion::id, Function.identity()));
    }

    @Test
    @DisplayName("全部达标 → passed=true，胜率/盈亏比/RR 判据通过")
    void allPass() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                goodFull(), seg(140, 45.0, 1.6, 40.0, 8.0, 90.0, 0.64),
                seg(60, 45.0, 1.6, 20.0, 4.0, 30.0, 0.5));
        assertTrue(ev.passed());
        Map<String, Criterion> m = byId(ev);
        assertTrue(m.get("SAMPLE_SIZE").pass());
        assertTrue(m.get("SPREAD_EXPECTANCY").pass());
        assertTrue(m.get("OOS_POSITIVE").pass());
        assertTrue(m.get("MAX_DRAWDOWN").pass());
        assertTrue(m.get("WINRATE_RR").pass());
        assertTrue(ev.verdict().contains("ACTIVE"));
    }

    @Test
    @DisplayName("样本量不足（99 < 100）→ 判据不过且回执边界")
    void sampleSizeInsufficient() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                seg(99, 45.0, 1.6, 60.0, 12.0, 50.0, 0.5), goodFull(), goodFull());
        assertFalse(ev.passed());
        Criterion c = byId(ev).get("SAMPLE_SIZE");
        assertFalse(c.pass());
        assertTrue(c.actual().contains("99"));
        assertTrue(c.threshold().contains("100"));
    }

    @Test
    @DisplayName("高成本档期望为负 → 脆弱，判据不过")
    void spreadExpectancyNegative() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                seg(200, 40.0, 0.9, 80.0, 16.0, -50.0, -0.25), goodFull(), goodFull());
        assertFalse(ev.passed());
        assertFalse(byId(ev).get("SPREAD_EXPECTANCY").pass());
        assertTrue(byId(ev).get("SPREAD_EXPECTANCY").message().contains("脆弱"));
    }

    @Test
    @DisplayName("样本外转负 → 过拟合嫌疑，判据不过且 A→B 退化")
    void oosNegative() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                goodFull(), seg(140, 45.0, 1.6, 40.0, 8.0, 90.0, 0.64),
                seg(60, 30.0, 0.8, 30.0, 6.0, -20.0, -0.33));
        assertFalse(ev.passed());
        assertFalse(byId(ev).get("OOS_POSITIVE").pass());
        assertTrue(ev.abConclusion().contains("过拟合"));
    }

    @Test
    @DisplayName("回撤超限（16% > 15%）→ 判据不过")
    void drawdownExceeded() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                seg(200, 45.0, 1.6, 80.0, 16.0, 120.0, 0.6), goodFull(), goodFull());
        assertFalse(ev.passed());
        Criterion c = byId(ev).get("MAX_DRAWDOWN");
        assertFalse(c.pass());
        assertTrue(c.threshold().contains("15"));
    }

    @Test
    @DisplayName("胜率与盈亏比不匹配（R=2 需 >33.33%，实测 33.33% 不满足）")
    void winRateRrMismatch() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                seg(200, 33.33, 1.6, 60.0, 12.0, 120.0, 0.6), goodFull(), goodFull());
        assertFalse(ev.passed());
        assertFalse(byId(ev).get("WINRATE_RR").pass());
        assertTrue(byId(ev).get("WINRATE_RR").message().contains("33.33"));
    }

    @Test
    @DisplayName("两策略 A/B 结论文本可读（含样本内外对比）")
    void abConclusionIsReadable() {
        StrategyGateEvaluator.Evaluation ev = StrategyGateEvaluator.evaluate(cfg(), 2.0,
                goodFull(), seg(140, 45.0, 1.6, 40.0, 8.0, 90.0, 0.64),
                seg(60, 44.0, 1.5, 20.0, 4.0, 30.0, 0.5));
        assertTrue(ev.abConclusion().contains("样本内(A)"));
        assertTrue(ev.abConclusion().contains("样本外(B)"));
        assertEquals(true, ev.passed());
    }
}
