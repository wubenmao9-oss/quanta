package com.benmao.quanta.backtest;

/** 多腿回测引擎模式：{@code MARTIN}=马丁整轮均价平仓；{@code GRID}=双边挂单网格逐腿独立平仓。 */
public enum BasketEngineMode {
    MARTIN,
    GRID
}
