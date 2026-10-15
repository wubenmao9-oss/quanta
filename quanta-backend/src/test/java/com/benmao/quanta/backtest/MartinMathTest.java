package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 马丁/网格机械手数算法单测：与《策略.txt》结果表口径一致（GOLD：100 points = 1.00 USD）。
 */
class MartinMathTest {

    /** 文章口径：权益 10000、baseLotPct 0.001(%)、step 0.01、maxLevels 5、multiplier 2。 */
    private static final double BASE = MartinMath.baseVolume(10000, 0.001, 0.01, 0.01, 2.0, 2.0, 5);

    @Test
    @DisplayName("基准手数：10000 权益 × 0.001% → 0.1 手")
    void baseVolumeMatchesArticle() {
        assertEquals(0.1, BASE, 1e-9);
    }

    @Test
    @DisplayName("基准手数封顶：第 maxLevels 腿不超过 maxVolume（任意账户规模 R2 恒可满足）")
    void baseVolumeCappedForHugeEquity() {
        // 权益极大时 raw 很大，cap = maxVolume / multiplier^(maxLevels-1) = 2.0 / 16 = 0.125，
        // 向下取整到手数步长（0.01）得 0.12（0.125 不是 0.01 的整数倍，且向上舍入会破上界）
        double v = MartinMath.baseVolume(10_000_000, 0.001, 0.01, 0.01, 2.0, 2.0, 5);
        assertEquals(0.12, v, 1e-9);
        // 第 5 腿 = 0.12 × 2^4 = 1.92，不超 maxVolume
        double leg5 = MartinMath.legVolume(v, 5, 2.0);
        assertEquals(1.92, leg5, 1e-9);
        assertTrue(leg5 <= 2.0, "封顶保证：第 maxLevels 腿手数必须 ≤ maxVolume");
    }

    @Test
    @DisplayName("腿手数序列：0.1 → 0.2 → 0.4 → 0.8 → 1.6")
    void legVolumeSequence() {
        assertEquals(0.1, MartinMath.legVolume(BASE, 1, 2.0), 1e-9);
        assertEquals(0.2, MartinMath.legVolume(BASE, 2, 2.0), 1e-9);
        assertEquals(0.4, MartinMath.legVolume(BASE, 3, 2.0), 1e-9);
        assertEquals(0.8, MartinMath.legVolume(BASE, 4, 2.0), 1e-9);
        assertEquals(1.6, MartinMath.legVolume(BASE, 5, 2.0), 1e-9);
    }

    @Test
    @DisplayName("单腿止损：多头在下方、空头在上方（100 points = 1.00 USD）")
    void legSl() {
        assertEquals(1999.0, MartinMath.legSl(RiskSide.BUY, 2000.0, 100, 0.01), 1e-9);
        assertEquals(2001.0, MartinMath.legSl(RiskSide.SELL, 2000.0, 100, 0.01), 1e-9);
    }

    @Test
    @DisplayName("整轮均价止盈：均价 ± tpPoints×point×R（与腿兜底 SL 距离解耦）")
    void basketTp() {
        assertEquals(2001.0, MartinMath.basketTp(RiskSide.BUY, 2000.0, 100, 0.01, 1.0), 1e-9);
        // 距离 = 100 × 0.01 × 2.0 = 2.0 → 1999 − 2.0 = 1997.0
        assertEquals(1997.0, MartinMath.basketTp(RiskSide.SELL, 1999.0, 100, 0.01, 2.0), 1e-9);
        // 解耦验证：腿兜底 SL 放宽到 500 点（5.00 USD）时，整轮止盈仍按 tpPoints=100 计 → 2001.0，
        // 不会因为放宽兜底 SL 而被一并拖到 +5.00（2005.0）
        assertEquals(1995.0, MartinMath.legSl(RiskSide.BUY, 2000.0, 500, 0.01), 1e-9);
        assertEquals(2001.0, MartinMath.basketTp(RiskSide.BUY, 2000.0, 100, 0.01, 1.0), 1e-9);
    }

    @Test
    @DisplayName("补腿触发价：多头在上腿入场价下方一个补腿距离")
    void addTrigger() {
        assertEquals(1999.0, MartinMath.addTrigger(RiskSide.BUY, 2000.0, 100, 0.01), 1e-9);
        assertEquals(2001.0, MartinMath.addTrigger(RiskSide.SELL, 2000.0, 100, 0.01), 1e-9);
    }

    @Test
    @DisplayName("加权均价：按手数加权；空腿返回 NaN")
    void avgEntry() {
        List<BasketLeg> legs = List.of(
                new BasketLeg(1, RiskSide.BUY, 0.1, 0, 2000.0, 1999.0, null),
                new BasketLeg(2, RiskSide.BUY, 0.2, 0, 1999.0, 1998.0, null));
        // (0.1×2000 + 0.2×1999) / 0.3 = 599.8 / 0.3
        assertEquals(599.8 / 0.3, MartinMath.avgEntry(legs), 1e-9);
        assertTrue(Double.isNaN(MartinMath.avgEntry(List.of())));
    }
}
