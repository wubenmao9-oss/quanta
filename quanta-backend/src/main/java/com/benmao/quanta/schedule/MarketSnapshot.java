package com.benmao.quanta.schedule;

import com.benmao.mt5.model.KlineBar;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 盯盘行情快照：MarketWatcher 每次轮询产出、TriggerEngine 只读消费。
 *
 * <p>纯内存对象，不含任何 AI/LLM 语义——这正是「盯盘全程不耗 token」的体现。
 *
 * @param symbol       品种（券商真实名）
 * @param timeframe    K 线周期
 * @param barTime      最新一根 K 线的开盘时间
 * @param bid          买价
 * @param ask          卖价
 * @param spreadPoints 点差（MT5 points 整数）
 * @param last         最新收盘价
 * @param high         最新 K 线最高价
 * @param low          最新 K 线最低价
 * @param bars         拉取到的 K 线（升序，末尾为最新）
 * @param capturedAt   快照生成时刻
 */
public record MarketSnapshot(
        String symbol,
        String timeframe,
        LocalDateTime barTime,
        double bid,
        double ask,
        int spreadPoints,
        double last,
        double high,
        double low,
        List<KlineBar> bars,
        Instant capturedAt
) {
}
