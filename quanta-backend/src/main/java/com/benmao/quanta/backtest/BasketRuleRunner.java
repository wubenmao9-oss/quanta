package com.benmao.quanta.backtest;

import java.util.List;

/**
 * 多腿/网格策略的<b>有状态运行实例</b>（与 {@link RuleRunner} 并存）：一次回测一个实例，逐根 K 线推进。
 *
 * <p>实现约定：只能经 {@link BasketState#view()}（游标 ≤ 当前）读取行情；每根 K 线都被调用一次以增量维护指标；
 * 返回动作序列（可空），由引擎按序应用。
 */
public interface BasketRuleRunner {

    /** 需要的最少 K 线根数（预热期），不足则不出动作。 */
    int warmupBars();

    /** 在游标处推进一根 K 线并给出动作序列（无动作为空列表）。 */
    List<BasketAction> onBar(BasketState state);
}
