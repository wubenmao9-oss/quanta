package com.benmao.quanta.backtest;

import java.util.Map;

/**
 * 规则策略（RULE 模式）：把策略中<b>可量化部分</b>用规则实现，回测与实盘共用（设计 §13.2）。
 *
 * <p>策略本身是无状态的原型 Bean；每次回测通过 {@link #newRunner()} 创建独立的有状态运行实例，
 * 避免并发回测串状态。
 */
public interface RuleStrategy {

    /** 策略唯一键（用于接口入参 / 落库 params 快照）。 */
    String key();

    /** 策略中文名（报告展示）。 */
    String displayName();

    /** 为一次回测创建独立运行实例。 */
    RuleRunner newRunner();

    /**
     * 策略的指标参数快照（用于回测落库 params / strategy.indicator_params）。
     *
     * <p>各策略按自身参数集中配置返回，便于留痕与复现；默认空。
     */
    default Map<String, Object> indicatorParams() {
        return Map.of();
    }
}
