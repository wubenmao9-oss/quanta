package com.benmao.quanta.backtest;

import java.util.Map;

/**
 * 多腿/网格策略原型 Bean（与 {@link RuleStrategy} 并存）：无状态原型，每次回测经 {@link #newRunner()}
 * 创建独立有状态实例，避免并发回测串状态。
 *
 * <p>同一策略可<b>按盘面形态</b>在马丁（趋势）与网格（震荡）两种子模式间切换，
 * 故引擎模式由 {@link BasketAction.OpenCycle} 逐轮携带，而非策略固定。
 */
public interface BasketStrategy {

    /** 策略唯一键（接口入参 / 落库 params 快照）。 */
    String key();

    /** 策略中文名（报告展示）。 */
    String displayName();

    /** 为一次回测创建独立运行实例。 */
    BasketRuleRunner newRunner();

    /** 策略指标参数快照（落库 params / strategy.indicator_params），默认空。 */
    default Map<String, Object> indicatorParams() {
        return Map.of();
    }
}
