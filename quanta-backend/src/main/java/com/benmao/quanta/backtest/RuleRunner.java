package com.benmao.quanta.backtest;

/**
 * 规则策略的<b>有状态运行实例</b>：一次回测一个实例，逐根 K 线推进。
 *
 * <p>实现约定：
 * <ul>
 *   <li>只能通过 {@link ReplayView}（游标 ≤ 当前）读取行情，绝不越界读未来；</li>
 *   <li>每根 K 线都会被调用一次（即使已持仓），以便增量维护指标状态；</li>
 *   <li>仅当未持仓且出现信号时返回 {@link EntrySignal}，否则返回 {@code null}。</li>
 * </ul>
 */
public interface RuleRunner {

    /** 需要的最少 K 线根数（预热期），不足则不出信号。 */
    int warmupBars();

    /**
     * 在游标处推进一根 K 线并给出（可选的）开仓意图。
     *
     * @param view     行情视图（只读、游标受限）
     * @param position 当前持仓（未持仓为 null）
     * @return 开仓意图；无动作为 null
     */
    EntrySignal onBar(ReplayView view, OpenPosition position);
}
