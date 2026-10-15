package com.benmao.quanta.backtest;

import com.benmao.quanta.risk.RiskSide;

/**
 * 多腿策略给出的<b>机械动作意图</b>（取代 {@link EntrySignal} 在多腿场景的角色；{@link EntrySignal} 完全不变）。
 *
 * <p>手数/挂单价格等<b>机械计算由引擎完成</b>，runner 只表达「何时做何种动作」；
 * 引擎严格按序应用，且锁定期内会丢弃开轮/补腿/挂单动作。
 */
public sealed interface BasketAction {

    /** 开新轮（引擎按当前净值算基准手数与第 1 腿，并生成整轮止盈价）；{@code mode} 决定该轮子模式。 */
    record OpenCycle(RiskSide side, BasketEngineMode mode) implements BasketAction {
    }

    /** 逆势补腿（引擎按腿序号算手数并重算整轮均价/止盈价）。 */
    record AddLeg() implements BasketAction {
    }

    /** 平掉整轮（reason 落库为平仓原因：TP/SL/HARD_SL/DAY_LOCK/TOTAL_LOCK/MANUAL/END/MAX_LEVEL）。 */
    record CloseCycle(String reason) implements BasketAction {
    }

    /** 挂单（网格双边）。 */
    record PlacePending(GridPendingOrder.Kind kind, double price) implements BasketAction {
    }

    /** 撤销全部挂单（越界/锁定期/达上限）。 */
    record CancelAllPending() implements BasketAction {
    }

    /** 本根无动作。 */
    record Idle() implements BasketAction {
    }
}
