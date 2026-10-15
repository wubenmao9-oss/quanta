package com.benmao.quanta.strategy;

import com.benmao.quanta.strategy.StrategyGateReport.Criterion;
import com.benmao.quanta.strategy.StrategyGateReport.Segment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 策略回测验收门禁的<b>纯判据求值器</b>（无 IO、可单测）：把三段回测摘要映射为逐条门禁判据。
 *
 * <p>判据顺序与 P0清单 M9 一致：样本量 → 门禁点差档期望 → 样本外为正 → 最大回撤 → 胜率/盈亏比匹配。
 */
public final class StrategyGateEvaluator {

    private StrategyGateEvaluator() {
    }

    /**
     * @param cfg     门禁配置（阈值集中配置）
     * @param targetRr 策略目标盈亏比 R（胜率判据：胜率 &gt; 1/(1+R)）
     * @param full    全样本摘要（门禁点差档）
     * @param is      样本内(A)摘要
     * @param oos     样本外(B)摘要
     */
    public static Evaluation evaluate(StrategyProperties.Gate cfg, double targetRr,
                                      Segment full, Segment is, Segment oos) {
        return evaluate(cfg, targetRr, full, is, oos, "SINGLE_LEG");
    }

    /**
     * @param cfg     门禁配置（阈值集中配置）
     * @param targetRr 策略目标盈亏比 R（胜率判据：胜率 &gt; 1/(1+R)）
     * @param full    全样本摘要（门禁点差档）
     * @param is      样本内(A)摘要
     * @param oos     样本外(B)摘要
     * @param family  策略族：SINGLE_LEG（单仓口径）或 BASKET（轮级口径，附加 c6/c7）
     */
    public static Evaluation evaluate(StrategyProperties.Gate cfg, double targetRr,
                                      Segment full, Segment is, Segment oos, String family) {
        boolean basket = "BASKET".equalsIgnoreCase(family);
        List<Criterion> cs = new ArrayList<>();

        // c1 样本量：单仓按笔数、多腿/网格按轮数（门禁验收须以「轮」为单位，避免腿数虚高）
        int sample = basket ? full.cycleCount() : full.tradeCount();
        int minSample = basket ? cfg.getMinCycles() : cfg.getMinTradeCount();
        String sampleUnit = basket ? "轮" : "笔";
        boolean c1 = sample >= minSample;
        cs.add(new Criterion(basket ? "SAMPLE_SIZE_CYCLES" : "SAMPLE_SIZE", "最小" + sampleUnit + "样本量", c1,
                sample + " " + sampleUnit, "≥ " + minSample + " " + sampleUnit,
                c1 ? "样本量达标" : "样本不足：仅 " + sample + " " + sampleUnit + "，需 ≥ " + minSample + " " + sampleUnit));

        boolean c2 = full.expectancy() > 0;
        cs.add(new Criterion("SPREAD_EXPECTANCY", cfg.getSpreadPoints() + " 点差档期望为正", c2,
                fmt(full.expectancy()), "> 0",
                c2 ? "高成本档下期望为正 → 稳健（真 α）"
                        : "高成本档下期望为负 → 脆弱（利润被点差吃掉）"));

        boolean c3 = oos.expectancy() > 0;
        cs.add(new Criterion("OOS_POSITIVE", "样本外(B)期望为正", c3,
                fmt(oos.expectancy()), "> 0",
                c3 ? "外样本仍为正 → 未见过拟合"
                        : "外样本转负 → 过拟合嫌疑，须回炉"));

        boolean c4 = full.maxDrawdownPct() <= cfg.getMaxDrawdownPct();
        cs.add(new Criterion("MAX_DRAWDOWN", "最大回撤≤上限", c4,
                fmt(full.maxDrawdownPct()) + "%", "≤ " + fmt(cfg.getMaxDrawdownPct()) + "%",
                c4 ? "回撤在账户级阈值内" : "回撤超限：" + fmt(full.maxDrawdownPct()) + "% > " + fmt(cfg.getMaxDrawdownPct()) + "%"));

        double needed = 100.0 / (1.0 + targetRr);
        boolean c5 = full.winRatePct() > needed;
        cs.add(new Criterion("WINRATE_RR", (basket ? "轮" : "") + "胜率 > 1/(1+R)", c5,
                fmt(full.winRatePct()) + "%", "> " + fmt(needed) + "%（R=" + fmt(targetRr) + "）",
                c5 ? "胜率与盈亏比匹配" : "胜率不足：需 > " + fmt(needed) + "%（R=" + fmt(targetRr) + "）"));

        // c6/c7 仅多腿/网格（BASKET）：马丁类须额外校验连亏轮与平均档数，避免腿级指标虚高导致门禁失效
        if (basket) {
            boolean c6 = full.maxLosingCycles() <= cfg.getMaxLosingCycles();
            cs.add(new Criterion("MAX_LOSING_CYCLES", "最大连亏轮数≤上限", c6,
                    full.maxLosingCycles() + " 轮", "≤ " + cfg.getMaxLosingCycles() + " 轮",
                    c6 ? "连亏轮在阈值内" : "连亏轮超限：" + full.maxLosingCycles() + " > " + cfg.getMaxLosingCycles()));

            boolean c7 = full.avgLevelsPerCycle() <= cfg.getMaxAvgLevels();
            cs.add(new Criterion("CYCLE_AVG_LEVELS", "平均每轮档数≤上限", c7,
                    fmt(full.avgLevelsPerCycle()), "≤ " + fmt(cfg.getMaxAvgLevels()),
                    c7 ? "平均档数在阈值内（未失控加仓）"
                            : "平均档数超限：" + fmt(full.avgLevelsPerCycle()) + " > " + fmt(cfg.getMaxAvgLevels())));
        }

        boolean passed = cs.stream().allMatch(Criterion::pass);
        return new Evaluation(cs, passed, abConclusion(is, oos),
                verdict(passed, cs));
    }

    /** 样本内(A) → 样本外(B) 对比结论（防过拟合的直接证据）。 */
    private static String abConclusion(Segment is, Segment oos) {
        String head = "样本内(A) 交易 " + is.tradeCount() + " 笔 / 胜率 " + fmt(is.winRatePct())
                + "% / 期望 " + fmt(is.expectancy())
                + " → 样本外(B) 交易 " + oos.tradeCount() + " 笔 / 胜率 " + fmt(oos.winRatePct())
                + "% / 期望 " + fmt(oos.expectancy()) + "；";
        if (oos.tradeCount() == 0) {
            return head + "样本外无成交，无法判定外样本稳健性";
        }
        return head + (oos.expectancy() > 0 ? "外样本仍为正 → A→B 一致，未见过拟合"
                : "外样本转负 → A→B 退化，过拟合风险，须回炉调参");
    }

    private static String verdict(boolean passed, List<Criterion> cs) {
        if (passed) {
            return "门禁全部通过，可提交人工批准并置为 ACTIVE";
        }
        List<String> failed = new ArrayList<>();
        for (Criterion c : cs) {
            if (!c.pass()) {
                failed.add(c.name());
            }
        }
        return "门禁未通过：" + String.join("、", failed) + "；须回炉调参后重新回测";
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 求值结果。 */
    public record Evaluation(List<Criterion> criteria, boolean passed, String abConclusion, String verdict) {
    }
}
